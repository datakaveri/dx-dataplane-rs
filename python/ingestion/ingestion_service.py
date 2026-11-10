import pika
import json
import sys
import time
import requests
import secrets
import string
from requests.auth import HTTPBasicAuth

def clean_permission_pattern(pattern):
    """Clean up permission pattern by removing duplicates and empty patterns"""
    if not pattern or pattern == '^$':
        return '^$'
    
    # Split by pipe and remove duplicates
    patterns = pattern.split('|')
    unique_patterns = []
    
    for p in patterns:
        p = p.strip()
        if p and p != '^$' and p not in unique_patterns:
            unique_patterns.append(p)
    
    # Join back with pipe
    if not unique_patterns:
        return '^$'
    elif len(unique_patterns) == 1:
        return unique_patterns[0]
    else:
        return '|'.join(unique_patterns)

def generate_random_password(length=16):
    """Generate a secure random password."""
    alphabet = string.ascii_letters + string.digits + string.punctuation
    return ''.join(secrets.choice(alphabet) for _ in range(length))

def check_user_exists(host, port, auth, user_id):
    """Check if a user already exists in RabbitMQ"""
    try:
        base_url = f"http://{host}:{port}/api"
        user_url = f"{base_url}/users/{user_id}"
        response = requests.get(user_url, auth=auth, timeout=10)
        return response.status_code == 200
    except:
        return False

def setup_rabbitmq_amqp(config):
    """Set up RabbitMQ using AMQP protocol"""
    try:
        print("Setting up RabbitMQ using AMQP protocol...")
        print(f"Connecting to {config['host']}:24568")
        
        # AMQP connection parameters
        credentials = pika.PlainCredentials(config['username'], config['password'])
        parameters = pika.ConnectionParameters(
            host=config['host'],
            port=24568,
            virtual_host=config['vhost'],
            credentials=credentials,
            heartbeat=600,
            blocked_connection_timeout=300,
            connection_attempts=3,
            retry_delay=5
        )
        
        connection = pika.BlockingConnection(parameters)
        channel = connection.channel()
        
        asset_id = config['asset_id']
        db_queue = config['database_queue']
        sub_queue = config['subscription_queue']
        
        # Create exchange
        channel.exchange_declare(
            exchange=asset_id,
            exchange_type='topic',
            durable=True,
            auto_delete=False
        )
        print(f"✓ Exchange '{asset_id}' created")
        
        # Create and bind database queue
        channel.queue_declare(queue=db_queue, durable=True)
        channel.queue_bind(
            exchange=asset_id,
            queue=db_queue,
            routing_key=asset_id
        )
        print(f"✓ Queue '{db_queue}' created and bound")
        
        # Create and bind subscription queue
        channel.queue_declare(queue=sub_queue, durable=True)
        channel.queue_bind(
            exchange=asset_id,
            queue=sub_queue,
            routing_key=asset_id
        )
        print(f"Queue '{sub_queue}' created and bound")
        
        connection.close()
        print("Adapter setup completed successfully!")
        return True
        
    except Exception as e:
        print(f"✗ Error setting up RabbitMQ: {e}")
        return False

def setup_user_and_permissions(config):
    """Create user and set up WRITE-only permissions, preserving existing ones"""
    try:
        print("\nSetting up user and WRITE permissions...")
        
        base_url = f"http://{config['host']}:28042/api"
        auth = HTTPBasicAuth(config['username'], config['password'])
        
        user_id = config['user_id']
        vhost = config['vhost']
        
        # Check if user already exists
        user_exists = check_user_exists(config['host'], 28042, auth, user_id)
        
        if user_exists:
            print(f"✓ User '{user_id}' already exists")
            # Cannot retrieve existing password due to security restrictions
            # We'll use a placeholder and inform the user
            config['user_password'] = "EXISTING_PASSWORD_PRESERVED"
            user_password_message = "EXISTING_PASSWORD_PRESERVED"
        else:
            # Generate random password for new user
            user_password = generate_random_password()
            config['user_password'] = user_password
            user_password_message = user_password
            
            # Create the new user
            print(f"Creating user '{user_id}'...")
            user_url = f"{base_url}/users/{user_id}"
            user_data = {
                "password": user_password,
                "tags": ""  # No special tags
            }
            response = requests.put(user_url, auth=auth, json=user_data, timeout=10)
            
            if response.status_code in [200, 201, 204]:
                print(f"User '{user_id}' created successfully")
            else:
                print(f"Failed to create user: HTTP {response.status_code} - {response.text}")
                return False
        
        # Get current permissions (if any) to preserve them
        permissions_url = f"{base_url}/permissions/{vhost}/{user_id}"
        current_permissions = {"configure": "^$", "write": "^$", "read": "^$"}
        
        try:
            response = requests.get(permissions_url, auth=auth, timeout=10)
            if response.status_code == 200:
                current_permissions = response.json()
                print(f"✓ Found existing permissions for user '{user_id}':")
                print(f"  Configure: {current_permissions.get('configure', 'None')}")
                print(f"  Write:     {current_permissions.get('write', 'None')}")
                print(f"  Read:      {current_permissions.get('read', 'None')}")
        except:
            print("No existing permissions found, will create new ones")
        
        # Build new permissions by combining old + new
        asset_id = config['asset_id']
        db_queue = config['database_queue']
        sub_queue = config['subscription_queue']
        
        # New resources to add WRITE permission for
        new_write_resources = f"^{asset_id}$|^{db_queue}$|^{sub_queue}$"
        
        # Get current permissions
        current_configure = current_permissions.get('configure', '^$')
        current_write = current_permissions.get('write', '^$') 
        current_read = current_permissions.get('read', '^$')
        
        # Build new permissions - append new resources to WRITE only
        if current_write == '^$':  # If no existing write permissions
            new_write = new_write_resources
        else:
            # Remove empty pattern if it exists, then append new resources
            current_write = current_write.replace('^$|', '').replace('|^$', '')
            new_write = f"{current_write}|{new_write_resources}"
        
        # For configure and read, keep existing but ensure they're not empty
        new_configure = current_configure if current_configure != '^$' else '^$'
        new_read = current_read if current_read != '^$' else '^$'
        
        # Clean up the permission patterns
        new_write = clean_permission_pattern(new_write)
        new_configure = clean_permission_pattern(new_configure)
        new_read = clean_permission_pattern(new_read)
        
        permissions_data = {
            "configure": new_configure,
            "write": new_write,
            "read": new_read
        }
        
        print(f"\nSetting updated permissions:")
        print(f"  Configure: {new_configure}")
        print(f"  Write:     {new_write}")
        print(f"  Read:      {new_read}")
        
        # Delete old permissions first (RabbitMQ requires this for proper update)
        try:
            requests.delete(permissions_url, auth=auth, timeout=10)
        except:
            pass  # Ignore if delete fails (might not exist)
        
        # Set new permissions
        response = requests.put(permissions_url, auth=auth, json=permissions_data, timeout=10)
        
        if response.status_code in [200, 201, 204]:
            print(f"✓ Permissions updated successfully for user '{user_id}'")
            return True, user_exists
        else:
            print(f" Failed to set permissions: HTTP {response.status_code} - {response.text}")
            return False, user_exists
            
    except Exception as e:
        print(f"Error in user/permission setup: {e}")
        return False, False

def verify_permissions(config):
    """Verify that the permissions were set correctly"""
    try:
        print("\nVerifying final permissions...")
        
        base_url = f"http://{config['host']}:28042/api"
        auth = HTTPBasicAuth(config['username'], config['password'])
        
        user_id = config['user_id']
        vhost = config['vhost']
        permissions_url = f"{base_url}/permissions/{vhost}/{user_id}"
        
        response = requests.get(permissions_url, auth=auth, timeout=10)
        
        if response.status_code == 200:
            permissions = response.json()
            print("✓ Final permissions set:")
            print(f"  Configure: {permissions.get('configure', 'None')}")
            print(f"  Write:     {permissions.get('write', 'None')}")
            print(f"  Read:      {permissions.get('read', 'None')}")
            
            # Check if our resources are in write permission
            write_permission = permissions.get('write', '')
            resources_to_check = [
                f"^{config['asset_id']}$",
                f"^{config['database_queue']}$",
                f"^{config['subscription_queue']}$"
            ]
            
            all_resources_present = all(resource in write_permission for resource in resources_to_check)
            
            if all_resources_present:
                print("✓ All required resources have WRITE permission")
                return True
            else:
                print(" Some resources missing from WRITE permission")
                return False
        else:
            print("Could not verify permissions")
            return False
            
    except Exception as e:
        print(f"Error verifying permissions: {e}")
        return False

def display_connection_details(config, user_existed):
    """Display the connection details after successful registration"""
    print("\n" + "="*60)
    print("REGISTRATION SUCCESSFUL - CONNECTION DETAILS")
    print("="*60)
    print(f"User ID:       {config['user_id']}")
    print(f"Exchange Name: {config['asset_id']}")
    
    if user_existed:
        print(f"Password:      [USER ALREADY EXISTS - PASSWORD PRESERVED]")
        print("User already existed - using existing password")
    else:
        print(f"Password:      {config['user_password']}")
        print("Save this password - it cannot be retrieved again!")
    
    print("="*60)
    print("\nUse these credentials to connect to RabbitMQ:")
    print(f"Host: {config['host']}:24568")
    print(f"VHost: {config['vhost']}")
    print(f"Exchange: {config['asset_id']}")
    print(f"Routing Key: {config['asset_id']}")

def main():
    if len(sys.argv) < 2:
        print("Usage: python ingestion_service.py <config.json>")
        sys.exit(1)
    
    try:
        with open(sys.argv[1]) as f:
            config = json.load(f)
    except FileNotFoundError:
        print(f"Config file '{sys.argv[1]}' not found.")
        sys.exit(1)
    except json.JSONDecodeError:
        print(f"Config file '{sys.argv[1]}' contains invalid JSON.")
        sys.exit(1)
    
    # Run the AMQP setup
    success = setup_rabbitmq_amqp(config)
    
    # Set up user and permissions with correct append logic
    if success:
        permission_success, user_existed = setup_user_and_permissions(config)
        
        if permission_success:
            # Verify the permissions were set correctly
            verify_success = verify_permissions(config)
            if verify_success:
                print("\nCOMPLETE SUCCESS! RabbitMQ setup with correct permission management!")
                
                # Display the connection details
                display_connection_details(config, user_existed)
                
            else:
                print("\n  Setup completed but permission verification failed")
        else:
            print("\n  RabbitMQ setup successful but permission configuration failed.")
    else:
        print("\n❌ RabbitMQ setup failed.")
    
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
