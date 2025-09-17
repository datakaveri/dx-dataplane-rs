import requests
import json

# Load config from file
with open("config.json", "r") as f:
    config = json.load(f)

URL = config["url"]
TOKEN = config["token"]
payload = config["payload"]

url = f"https://{URL}/admin/elasticsearch/createIndex"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {TOKEN}"
}

try:
    response = requests.post(url, headers=headers, data=json.dumps(payload))
    print("Status Code:", response.status_code)
    print("Response:", response.text)
except requests.exceptions.RequestException as e:
    print("Error while making request:", e)

