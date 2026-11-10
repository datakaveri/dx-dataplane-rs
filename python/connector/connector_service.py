import json
import requests
import sys
import random
import string

class ConnectorService:
    def __init__(self, config_path):
        with open(config_path) as f:
            self.config = json.load(f)
        self.mgmt_url = f"http://{self.config['rabbitmq_host']}:{self.config['rabbitmq_management_port']}/api"
        self.auth = (self.config['rabbitmq_user'], self.config['rabbitmq_password'])
        self.vhost = self.config['vhost']
        self.publish_exchange = self.config['publish_exchange']
        self.existing_users = {}  # Cache for existing users

    def clean_permission_pattern(self, pattern):
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

    def check_user_exists(self, username):
        """Check if user exists without modifying password"""
        if username in self.existing_users:
            return self.existing_users[username]
        
        url = f"{self.mgmt_url}/users/{username}"
        r = requests.get(url, auth=self.auth)
        
        if r.status_code == 200:
            self.existing_users[username] = True
            return True
        elif r.status_code == 404:
            self.existing_users[username] = False
            return False
        else:
            raise Exception(f"Failed to check user: {r.text}")

    def create_user_with_random_password(self, username):
        """Create user with random password only if it doesn't exist"""
        # First check if user exists
        user_exists = self.check_user_exists(username)
        
        if user_exists:
            # User exists, we cannot retrieve the password due to security reasons
            # Return a message indicating the password should be used from previous setup
            return "USE_EXISTING_PASSWORD"
        else:
            # User doesn't exist, create with random password
            url = f"{self.mgmt_url}/users/{username}"
            password = ''.join(random.choices(string.ascii_letters + string.digits, k=16))
            data = {"password": password, "tags": ""}
            r = requests.put(url, auth=self.auth, json=data)
            
            if r.status_code not in (201, 204):
                raise Exception(f"Failed to create user: {r.text}")
            
            self.existing_users[username] = True
            return password

    def set_vhost_permissions(self, username, asset_id):
        """Set permissions for user, preserving existing permissions"""
        url = f"{self.mgmt_url}/permissions/{self.vhost}/{username}"
        
        # Get current permissions first
        try:
            r = requests.get(url, auth=self.auth)
            if r.status_code == 200:
                current_perms = r.json()
                print(f"✓ Found existing permissions for user '{username}':")
                print(f"  Configure: {current_perms.get('configure', 'None')}")
                print(f"  Write:     {current_perms.get('write', 'None')}")
                print(f"  Read:      {current_perms.get('read', 'None')}")
            else:
                current_perms = {"configure": "^$", "write": "^$", "read": "^$"}
                print("No existing permissions found, will create new ones")
        except:
            current_perms = {"configure": "^$", "write": "^$", "read": "^$"}
            print("Could not retrieve current permissions, will create new ones")
        
        # Build new permissions by combining old + new
        configure_pattern = current_perms.get('configure', '^$')
        write_pattern = current_perms.get('write', '^$')
        read_pattern = current_perms.get('read', '^$')
        
        # Add asset_id to write permissions
        new_write_resource = f"^{asset_id}$"
        if write_pattern == '^$':
            new_write = new_write_resource
        else:
            # Remove empty pattern if it exists, then append new resource
            write_pattern = write_pattern.replace('^$|', '').replace('|^$', '')
            new_write = f"{write_pattern}|{new_write_resource}"
        
        # Also allow writing to default exchange
        new_write = f"{new_write}|^amq.default$"
        
        # Add asset_id to read permissions
        new_read_resource = f"^{asset_id}$"
        if read_pattern == '^$':
            new_read = new_read_resource
        else:
            # Remove empty pattern if it exists, then append new resource
            read_pattern = read_pattern.replace('^$|', '').replace('|^$', '')
            new_read = f"{read_pattern}|{new_read_resource}"
        
        # Clean up the permission patterns
        new_configure = self.clean_permission_pattern(configure_pattern)
        new_write = self.clean_permission_pattern(new_write)
        new_read = self.clean_permission_pattern(new_read)
        
        perms = {
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
            requests.delete(url, auth=self.auth)
        except:
            pass  # Ignore if delete fails
        
        r = requests.put(url, auth=self.auth, json=perms)
        if r.status_code not in (201, 204):
            raise Exception(f"Failed to set vhost permissions: {r.text}")
        
        print(f"✓ Permissions updated successfully for user '{username}'")

    def create_queue(self, queue_name):
        url = f"{self.mgmt_url}/queues/{self.vhost}/{queue_name}"
        data = {
            "durable": True,
            "arguments": {
                "x-message-ttl": 60000,
                "x-max-length": 10000,
                "x-queue-mode": "default"
            }
        }
        r = requests.put(url, auth=self.auth, json=data)
        if r.status_code not in (201, 204):
            raise Exception(f"Failed to create queue: {r.text}")

    def bind_queue(self, exchange, queue, routing_key):
        url = f"{self.mgmt_url}/bindings/{self.vhost}/e/{exchange}/q/{queue}"
        data = {"routing_key": routing_key}
        r = requests.post(url, auth=self.auth, json=data)
        if r.status_code not in (201, 204):
            raise Exception(f"Failed to bind queue: {r.text}")

    def create_connector(self, user_id, asset_id):
        if not user_id or not asset_id:
            raise Exception("Invalid input or blank value")
        
        # Create user or get password status
        password_result = self.create_user_with_random_password(user_id)
        
        # Create queue
        self.create_queue(asset_id)
        
        # Bind queue to publish exchange
        self.bind_queue(self.publish_exchange, asset_id, asset_id)
        
        # Set permissions (now preserves existing permissions)
        self.set_vhost_permissions(user_id, asset_id)
        
        # Return connection details
        return {
            "user_id": user_id,
            "password": password_result,
            "queue_name": asset_id,
            "exchange": self.publish_exchange,
            "routing_key": asset_id,
            "host": self.config['rabbitmq_host'],
            "port": self.config['rabbitmq_port'],
            "vhost": self.vhost,
            "user_existed": password_result == "USE_EXISTING_PASSWORD"
        }

def main():
    if len(sys.argv) < 2:
        print("Usage: python create_connector.py <config.json>")
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
    
    # Get user_id and asset_id from config
    user_id = config.get('username')
    asset_id = config.get('asset_id')
    
    if not user_id or not asset_id:
        print("Error: username and asset_id must be specified in config.json")
        sys.exit(1)
    
    try:
        connector = ConnectorService(sys.argv[1])
        result = connector.create_connector(user_id, asset_id)
        
        print("\n" + "="*60)
        print("CONNECTOR CREATED SUCCESSFULLY")
        print("="*60)
        print(f"User ID:      {result['user_id']}")
        
        if result['user_existed']:
            print(f"Password:     [USER ALREADY EXISTS - USE EXISTING PASSWORD]")
            print("User already existed - please use the password from previous setup")
        else:
            print(f"Password:     {result['password']}")
            print("Save this password - it cannot be retrieved again!")
        
        print(f"Queue Name:   {result['queue_name']}")
        print(f"Exchange:     {result['exchange']}")
        print(f"Routing Key:  {result['routing_key']}")
        print(f"Host:         {result['host']}")
        print(f"Port:         {result['port']}")
        print(f"VHost:        {result['vhost']}")
        print("="*60)
        
    except Exception as e:
        print(f"Error creating connector: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
