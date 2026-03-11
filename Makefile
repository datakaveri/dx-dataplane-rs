.PHONY: docs docs-lint

## Bundle modular OpenAPI spec into a single file
docs:
	npx @redocly/cli bundle docs/dataplane-openapi/openapi.yaml -o docs/openapi.yaml

## Lint the modular OpenAPI spec
docs-lint:
	npx @redocly/cli lint docs/dataplane-openapi/openapi.yaml
