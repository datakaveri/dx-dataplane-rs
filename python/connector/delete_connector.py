import json
import requests
import sys

class ConnectorDeletionService:
    def __init__(self, config_path):
        with open(config_path) as f:
            self.config = json.load(f)
        self.mgmt_url = f"http://{self.config['rabbitmq_host']}:{self.config['rabbitmq_management_port']}/api"
        self.auth = (self.config['rabbitmq_user'], self.config['rabbitmq_password'])
        self.vhost = self.config['vhost']

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

    def remove_permissions_for_asset(self, username, asset_id):
        """Remove permissions for the specific asset while retaining all others"""
        url = f"{self.mgmt_url}/permissions/{self.vhost}/{username}"
        
        try:
            # Get current permissions
            r = requests.get(url, auth=self.auth)
            if r.status_code == 200:
                current_perms = r.json()
                print(f"✓ Current permissions for user '{username}':")
                print(f"  Configure: {current_perms.get('configure', 'None')}")
                print(f"  Write:     {current_perms.get('write', 'None')}")
                print(f"  Read:      {current_perms.get('read', 'None')}")
            else:
                print("ℹ️ No existing permissions found to remove")
                return
        except Exception as e:
            print(f"ℹ️ Could not retrieve current permissions: {e}")
            return
        
        # Remove the asset from all permission types
        patterns_to_remove = [f"^{asset_id}$", "^amq.default$"]
        
        for perm_type in ['configure', 'write', 'read']:
            current_pattern = current_perms.get(perm_type, '^$')
            if current_pattern != '^$':
                # Split and remove the asset patterns
                patterns = current_pattern.split('|')
                filtered_patterns = [p for p in patterns if p not in patterns_to_remove]
                
                # Clean up the pattern
                if filtered_patterns:
                    new_pattern = '|'.join(filtered_patterns)
                    current_perms[perm_type] = self.clean_permission_pattern(new_pattern)
                else:
                    current_perms[perm_type] = '^$'
        
        print(f"\nUpdated permissions after removing asset '{asset_id}':")
        print(f"  Configure: {current_perms.get('configure', 'None')}")
        print(f"  Write:     {current_perms.get('write', 'None')}")
        print(f"  Read:      {current_perms.get('read', 'None')}")
        
        # Delete old permissions first (RabbitMQ requires this for proper update)
        try:
            requests.delete(url, auth=self.auth, timeout=10)
        except:
            pass  # Ignore if delete fails (might not exist)
        
        # Set new permissions
        r = requests.put(url, auth=self.auth, json=current_perms, timeout=10)
        
        if r.status_code in (200, 201, 204):
            print(f"✓ Permissions updated successfully for user '{username}'")
        else:
            print(f"⚠️ Failed to update permissions: HTTP {r.status_code} - {r.text}")

    def unbind_queue(self, exchange, queue, routing_key):
        """Unbind queue from exchange"""
        # Get binding to find the properties key
        url = f"{self.mgmt_url}/bindings/{self.vhost}/e/{exchange}/q/{queue}"
        r = requests.get(url, auth=self.auth)
        
        if r.status_code == 200:
            bindings = r.json()
            for binding in bindings:
                if binding.get('routing_key') == routing_key:
                    # Delete the specific binding
                    props_key = binding.get('properties_key')
                    if props_key:
                        delete_url = f"{url}/{props_key}"
                        dr = requests.delete(delete_url, auth=self.auth)
                        if dr.status_code in (204, 404):
                            print(f"✓ Unbound queue '{queue}' from exchange '{exchange}'")
                        else:
                            print(f"⚠️ Failed to unbind queue: {dr.text}")
                    break
        else:
            print(f"ℹ️ No bindings found for queue '{queue}'")

    def delete_queue(self, queue_name):
        url = f"{self.mgmt_url}/queues/{self.vhost}/{queue_name}"
        r = requests.delete(url, auth=self.auth)
        if r.status_code in (204, 404):
            print(f"✓ Queue '{queue_name}' deleted")
        else:
            print(f"⚠️ Failed to delete queue: {r.text}")

    def delete_connector(self, user_id, asset_id):
        if not user_id or not asset_id:
            raise Exception("Invalid input or blank value")
        
        print(f"Starting deletion process for user '{user_id}', asset '{asset_id}'")
        
        # First unbind the queue
        self.unbind_queue(self.config.get('publish_exchange', 'rpc-adapter-requests'), asset_id, asset_id)
        
        # Then delete the queue
        self.delete_queue(asset_id)
        
        # Remove permissions for this asset (while preserving others)
        self.remove_permissions_for_asset(user_id, asset_id)
        
        print(f"✓ Connector for user '{user_id}' and asset '{asset_id}' deleted successfully")

def main():
    if len(sys.argv) < 2:
        print("Usage: python delete_connector.py <config.json>")
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
    
    # Confirm deletion
    print(f"WARNING: This will delete queue '{asset_id}' and remove permissions for asset '{asset_id}'")
    print(f"for user '{user_id}'. Other permissions for this user will be preserved.")
    confirmation = input("Are you sure you want to continue? (yes/no): ")
    
    if confirmation.lower() != 'yes':
        print("Deletion cancelled.")
        sys.exit(0)
    
    try:
        deleter = ConnectorDeletionService(sys.argv[1])
        deleter.delete_connector(user_id, asset_id)
        
    except Exception as e:
        print(f"Error deleting connector: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
