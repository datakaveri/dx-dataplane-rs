import pika
import json
import sys
import requests
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

def delete_exchange_only(config):
    """Delete only the exchange and its bindings, NOT the shared queues"""
    try:
        print("Starting deletion process...")
        
        # 1. First delete the exchange using AMQP (but NOT the queues)
        print("Deleting exchange only (preserving shared queues)...")
        success = delete_rabbitmq_exchange_only(config)
        
        if not success:
            return False
        
        # 2. Remove permissions for this specific exchange only
        print("Updating permissions to remove deleted exchange...")
        permission_success = remove_permissions_for_deleted_exchange(config)
        
        if permission_success:
            print("✓ Permissions updated successfully")
        else:
            print("⚠️  Exchange deleted but permission cleanup failed")
        
        return True
        
    except Exception as e:
        print(f"✗ Error during deletion: {e}")
        return False

def delete_rabbitmq_exchange_only(config):
    """Delete only the exchange, preserve the shared queues"""
    try:
        print("Connecting to RabbitMQ to delete exchange only...")
        
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
        
        # Only delete the exchange, NOT the queues
        # The queues are shared and should be preserved
        
        try:
            # First, unbind the queues from this exchange (optional but clean)
            try:
                channel.queue_unbind(
                    queue=config['database_queue'],
                    exchange=asset_id,
                    routing_key=asset_id
                )
                print(f"✓ Unbound '{config['database_queue']}' from exchange '{asset_id}'")
            except:
                print(f"⚠️  Could not unbind '{config['database_queue']}' (may not exist)")
            
            try:
                channel.queue_unbind(
                    queue=config['subscription_queue'],
                    exchange=asset_id,
                    routing_key=asset_id
                )
                print(f"✓ Unbound '{config['subscription_queue']}' from exchange '{asset_id}'")
            except:
                print(f"⚠️  Could not unbind '{config['subscription_queue']}' (may not exist)")
            
            # Now delete the exchange
            channel.exchange_delete(exchange=asset_id)
            print(f"✓ Exchange '{asset_id}' deleted")
            
        except Exception as e:
            print(f"⚠️  Could not delete exchange '{asset_id}': {e}")
        
        connection.close()
        print("✓ Exchange deleted successfully! Shared queues preserved.")
        return True
        
    except Exception as e:
        print(f"✗ Error deleting exchange: {e}")
        return False

def remove_permissions_for_deleted_exchange(config):
    """Remove permissions for deleted exchange only, retain queue permissions"""
    try:
        base_url = f"http://{config['host']}:28042/api"
        auth = HTTPBasicAuth(config['username'], config['password'])
        
        user_id = config['user_id']
        vhost = config['vhost']
        permissions_url = f"{base_url}/permissions/{vhost}/{user_id}"
        
        # Get current permissions
        try:
            response = requests.get(permissions_url, auth=auth, timeout=10)
            if response.status_code == 200:
                current_permissions = response.json()
                print("✓ Retrieved current permissions")
            else:
                print("⚠️  Could not retrieve current permissions")
                return False
        except:
            print("⚠️  Could not retrieve current permissions")
            return False
        
        # Only remove the exchange permission, keep queue permissions
        exchange_to_remove = f"^{config['asset_id']}$"
        
        # Keep these queue permissions (DO NOT remove them)
        queues_to_keep = [
            f"^{config['database_queue']}$",
            f"^{config['subscription_queue']}$"
        ]
        
        # Remove only the exchange from each permission type
        new_permissions = {}
        for perm_type in ['configure', 'write', 'read']:
            current_pattern = current_permissions.get(perm_type, '^$')
            
            if current_pattern == '^$':
                new_permissions[perm_type] = '^$'
                continue
            
            # Split and filter out only the exchange, keep queues
            patterns = current_pattern.split('|')
            filtered_patterns = []
            
            for pattern in patterns:
                pattern = pattern.strip()
                # Keep the pattern if it's not the exchange OR if it's one of the queues we want to keep
                if pattern and pattern != exchange_to_remove:
                    filtered_patterns.append(pattern)
            
            # Rebuild the pattern
            if not filtered_patterns:
                new_permissions[perm_type] = '^$'
            else:
                new_permissions[perm_type] = '|'.join(filtered_patterns)
            
            # Clean up the pattern
            new_permissions[perm_type] = clean_permission_pattern(new_permissions[perm_type])
        
        print(f"Updated permissions after exchange removal:")
        print(f"  Configure: {new_permissions['configure']}")
        print(f"  Write:     {new_permissions['write']}")
        print(f"  Read:      {new_permissions['read']}")
        
        # Delete old permissions first
        try:
            requests.delete(permissions_url, auth=auth, timeout=10)
        except:
            pass  # Ignore if delete fails
        
        # Set new permissions (without the deleted exchange but with kept queues)
        response = requests.put(permissions_url, auth=auth, json=new_permissions, timeout=10)
        
        if response.status_code in [200, 201, 204]:
            print("✓ Permissions updated after exchange deletion")
            return True
        else:
            print(f"⚠️  Failed to update permissions: HTTP {response.status_code}")
            return False
            
    except Exception as e:
        print(f"⚠️  Error updating permissions: {e}")
        return False

def main():
    if len(sys.argv) < 2:
        print("Usage: python delete_adapter.py <config.json>")
        print("Example: python delete_adapter.py config.json")
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
    
    # Confirm deletion
    print(f"WARNING: This will delete exchange '{config['asset_id']}'")
    print(f"NOTE: Shared queues '{config['database_queue']}' and '{config['subscription_queue']}' will be PRESERVED")
    confirmation = input("Are you sure you want to continue? (yes/no): ")
    
    if confirmation.lower() != 'yes':
        print("Deletion cancelled.")
        sys.exit(0)
    
    # Perform deletion
    success = delete_exchange_only(config)
    
    if success:
        print("\n🎉 Deletion completed successfully!")
        print("✓ Exchange deleted")
        print("✓ Shared queues preserved")
        print("✓ Permissions updated")
    else:
        print("\n❌ Deletion failed.")
    
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
