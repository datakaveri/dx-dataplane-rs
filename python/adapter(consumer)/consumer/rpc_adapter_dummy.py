import json
import re
import logging
import random
from dateutil import parser as date_parser
from elasticsearch import Elasticsearch, BadRequestError
from elasticsearch_dsl import Search, Q
import pika
from configparser import ConfigParser
from urllib.parse import quote

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# --------------------- RabbitMQ Setup ---------------------

class RabbitMqServerConfigure:
    def __init__(self, username, password, host, port, vhost, queue):
        self.username = username
        self.password = password
        self.host = host
        self.port = port
        self.vhost = vhost
        self.queue = queue

class RabbitmqServer:
    def __init__(self, server):
        self.server = server
        username = quote(self.server.username, safe='')
        password = quote(self.server.password, safe='')

        url = f"amqps://{username}:{password}@{self.server.host}:{self.server.port}/{self.server.vhost}"
        self.connection = pika.BlockingConnection(pika.URLParameters(url))
        self.channel = self.connection.channel()
        logging.info("Server started waiting for Messages")

    def start_server(self, on_request):
        self.channel.basic_qos(prefetch_count=1)
        self.channel.basic_consume(
            queue=self.server.queue,
            on_message_callback=on_request
        )
        self.channel.start_consuming()

    def publish(self, payload, rout_key, corr_id, method):
        message = json.dumps(payload)
        self.channel.basic_publish(
            exchange='',
            routing_key=rout_key,
            properties=pika.BasicProperties(correlation_id=corr_id),
            body=message
        )
        self.channel.basic_ack(delivery_tag=method.delivery_tag)
        logging.info("Message published to %s", rout_key)

# --------------------- Elasticsearch Search ---------------------

class SearchDatabase:
    def __init__(self, config):
        self.config = config

    def search_surat_itms_data(self, json_object, query, rout_key, corr_id, method):
        elk_config = self.config['elasticsearch']
        client = Elasticsearch(
            [f"http://{elk_config['databaseURI']}:{elk_config['databasePort']}"],
            basic_auth=(elk_config['databaseUser'], elk_config['databasePassword'])
        )
        index_name = elk_config['index_name']
        limit = json_object.get('limit')
        offset = json_object.get('offset')
        apiEndpoint = json_object.get('api')

        try:
            if apiEndpoint == '/ngsi-ld/v1/async/search':
                response_payload = {
                    "searchId": "48c279f3-ed90-4c05-bbfa-ffa91dd3d8a2",
                    "statusCode": 201
                }

            elif apiEndpoint == '/ngsi-ld/v1/async/status':
                searchId = json_object.get("searchId")
                if random.choice([True, False]):
                    response_payload = {
                        "statusCode": 200,
                        "results": [{
                            "status": "COMPLETE",
                            "progress": 100,
                            "file-download-url": "https://example.com/filename",
                            "searchId": searchId
                        }]
                    }
                else:
                    response_payload = {
                        "statusCode": 200,
                        "results": [{
                            "status": "IN_PROGRESS",
                            "progress": 70,
                            "searchId": searchId
                        }]
                    }

            elif query:
                if "options" in json_object and json_object["options"] == "count":
                    query_dict = query.to_dict()
                    query = {"query": {"bool": {"must": [query_dict]}}}
                    count_response = client.count(index=index_name, body=query)
                    count = count_response['count']
                    status_code = 204 if count == 0 else 200
                    response_payload = {"totalHits": count, "statusCode": status_code}
                    if limit: response_payload["limit"] = int(limit)
                    if offset: response_payload["offset"] = int(offset)

                else:
                    logging.info("Final query: %s", query)
                    search = Search(index=index_name).using(client).query(query)
                    if limit and offset:
                        search = search[int(offset):int(offset) + int(limit)]
                    elif limit:
                        search = search[:int(limit)]
                    elif offset:
                        search = search[int(offset):]
                    else:
                        search = search[0:10000]

                    response = search.execute()
                    hits = [hit.to_dict() for hit in response.hits]
                    total_hits = (
                        response.hits.total.value
                        if hasattr(response.hits.total, 'value')
                        else response.hits.total
                    )
                    status_code = 204 if total_hits == 0 else 200

                    response_payload = {
                        "results": hits,
                        "statusCode": status_code,
                        "totalHits": total_hits
                    }
                    if limit: response_payload["limit"] = int(limit)
                    if offset: response_payload["offset"] = int(offset)

            else:
                response_payload = {"statusCode": 400, "error": "Empty query"}

        except BadRequestError as e:
            logging.error("Elasticsearch query failed: %s", e)
            response_payload = {
                "statusCode": 400,
                "error": str(e),
                "type": getattr(e, "error", "BadRequestError")
            }
        except Exception as e:
            logging.exception("Unexpected error in search_surat_itms_data")
            response_payload = {"statusCode": 500, "error": str(e)}

        server.publish(response_payload, rout_key, corr_id, method)
        logging.info("Query Completed for Surat-ITMS data")

# --------------------- Query Builders ---------------------

def build_temporal_query(temporal):
    try:
        logging.info("temporal json: %s", temporal)

        # Pick time property, fallback to createdAt
        time_property = temporal.get("timeproperty", "createdAt")

        # Parse times
        start_time = date_parser.parse(temporal.get("time"))
        end_time = date_parser.parse(temporal.get("endtime"))

        logging.info("temporal query with property [%s]: %s", time_property, temporal)

        return Q("range", **{time_property: {"gte": start_time, "lte": end_time}})
    except Exception as e:
        logging.error("Invalid temporal query: %s", e)
        return None

def build_geo_query(request_json):
    geo_query_params = request_json.get('geo-query')
    if not geo_query_params:
        return None
    geo_type = geo_query_params.get('geometry', '').lower()
    if geo_type == 'polygon':
        return build_geo_polygon_query(request_json)
    elif geo_type == 'bbox':
        return build_geo_bbox_query(request_json)
    elif geo_type == 'linestring':
        return build_geo_linestring_query(request_json)
    else:
        return build_geo_circle_query(geo_query_params)

def build_geo_circle_query(geo_query_params):
    lat = geo_query_params['lat']
    lon = geo_query_params['lon']
    radius = geo_query_params['radius']
    return Q('geo_distance', distance=radius, location={"lat": lat, "lon": lon})

def build_geo_polygon_query(request_json):
    geo_query_params = request_json.get('geo-query')
    coordinates_list = json.loads(geo_query_params['coordinates'])
    coordinates_float = [[float(coord[0]), float(coord[1])] for coord in coordinates_list[0]]
    return Q('geo_shape', location={'shape': {'type': 'Polygon', 'coordinates': [coordinates_float]}, 'relation': geo_query_params['georel']})

def build_geo_bbox_query(request_json):
    geo_query_params = request_json.get('geo-query')
    coordinates_list = json.loads(geo_query_params['coordinates'])
    latitudes = [float(coord[1]) for coord in coordinates_list]
    latitudes.sort()
    return Q('geo_bounding_box', location={
        'top_left': {'lat': latitudes[1], 'lon': float(coordinates_list[0][0])},
        'bottom_right': {'lat': latitudes[0], 'lon': float(coordinates_list[1][0])}
    })

def build_geo_linestring_query(request_json):
    geo_query_params = request_json.get('geo-query')
    coordinates_list = json.loads(geo_query_params['coordinates'])
    coordinates_float = [[float(coord[0]), float(coord[1])] for coord in coordinates_list]
    return Q('geo_shape', location={'shape': {'type': 'linestring', 'coordinates': coordinates_float}, 'relation': geo_query_params['georel']})

def build_attribute_query(condition):
    if not condition:
        return None
    try:
        parts = re.split(r'(==|>=|<=|>|<)', condition)
        if len(parts) == 3:
            field, operator, value = [p.strip() for p in parts]
            if re.match(r'^-?\d+(\.\d+)?$', value):
                value = float(value)
            if operator == "==":
                return Q("term", **{field: value})
            else:
                return Q("range", **{field: {operator_map(operator): value}})
        return None
    except Exception as e:
        logging.error("Invalid attribute query: %s", e)
        return None

def operator_map(op):
    return {">=": "gte", "<=": "lte", ">": "gt", "<": "lt"}[op]

def build_combined_query_strict_id(ids, temporal_query=None, attribute_query=None, geo_query=None):
    """
    Build Elasticsearch bool query with mandatory exact ID match,
    then optional temporal, attribute, and geo filters.
    """
    must_clauses = []

    # Mandatory exact ID match
    if ids:
        if isinstance(ids, list) and len(ids) == 1:
            must_clauses.append(Q("term", **{"id.keyword": ids[0]}))
        else:
            must_clauses.append(Q("terms", **{"id.keyword": ids}))

    if temporal_query:
        must_clauses.append(temporal_query)
    if attribute_query:
        must_clauses.append(attribute_query)
    if geo_query:
        must_clauses.append(geo_query)

    return Q("bool", must=must_clauses)

def entity_supports_temporal(ids):
    temporal_ids = {"temporal-entity-id", "another-id"}
    return any(i in temporal_ids for i in ids)

# --------------------- Consumer ---------------------

def process_request(ch, method, properties, body):
    logging.info("Received request with body: %s", body)
    try:
        json_object = json.loads(body)
    except Exception as e:
        logging.error("Invalid JSON: %s", e)
        error_payload = {"statusCode": 400, "error": "Invalid JSON format"}
        server.publish(error_payload, properties.reply_to, properties.correlation_id, method)
        return

    rout_key = properties.reply_to
    corr_id = properties.correlation_id
    surat_itms_db_search = SearchDatabase(config=config)

    try:
        search_type = json_object.get("searchType", "")
        search_parts = search_type.split("_")

        ids = json_object.get("id")
        if not ids:
            error_payload = {"statusCode": 400, "error": "Missing mandatory field: id"}
            server.publish(error_payload, rout_key, corr_id, method)
            return

        temporal_query = None
        attribute_query = None
        geo_query = None

        if "geoSearch" in search_parts:
            geo_query = build_geo_query(json_object)

        if "temporalSearch" in search_parts or entity_supports_temporal(ids):
            temporal_query = build_temporal_query(json_object.get("temporal-query"))

        if "attributeSearch" in search_parts:
            attribute_query = build_attribute_query(json_object.get("attr-query"))

        combined_query = build_combined_query_strict_id(ids, temporal_query, attribute_query, geo_query)
        logging.info("Final query: %s", combined_query)

        surat_itms_db_search.search_surat_itms_data(json_object, combined_query, rout_key, corr_id, method)

    except Exception as e:
        logging.exception("Error in process_request")
        error_payload = {"statusCode": 500, "error": str(e)}
        server.publish(error_payload, rout_key, corr_id, method)

# --------------------- Main ---------------------

if __name__ == '__main__':
    config = ConfigParser(interpolation=None)
    files_read = config.read("../config/config.ini")
    print("Config loaded from:", files_read)
    print("Sections found:", config.sections())


    username = config["server_setup"]["username"]
    password = config["server_setup"]["password"]
    host = config["server_setup"]["host"]
    port = config["server_setup"]["port"]
    vhost = config["server_setup"]["vhost"]
    queue = config["collection_queue"]["queue"]

    server_configure = RabbitMqServerConfigure(username, password, host, port, vhost, queue)
    server = RabbitmqServer(server=server_configure)
    server.start_server(on_request=process_request)
