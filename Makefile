.PHONY: generate
generate:
	buf generate --template ktor-serialization-connect/src/test/resources/buf.gen.yaml -o ktor-serialization-connect buf.build/connectrpc/eliza
