package generator

import (
	"bytes"
	"strings"
	"testing"
)

func Test_loadTemplate(t *testing.T) {
	t.Parallel()

	if _, err := loadTemplate(); err != nil {
		t.Errorf("failed to load template: %v", err)
	}
}

func Test_template_idempotent_method_emits_get_route(t *testing.T) {
	t.Parallel()

	tmpl, err := loadTemplate()
	if err != nil {
		t.Fatalf("failed to load template: %v", err)
	}

	data := &serviceData{
		ProtoPackageName: "example.v1",
		JavaPackageName:  "com.example.v1",
		SourceFileName:   "example/v1/service.proto",
		Name:             "ExampleService",
		HasIdempotent:    true,
		Methods: []*methodData{
			{
				Name:           "GetThing",
				InputTypeName:  "GetThingRequest",
				OutputTypeName: "GetThingResponse",
				StreamType:     streamTypeUnary,
				Idempotent:     true,
			},
			{
				Name:           "CreateThing",
				InputTypeName:  "CreateThingRequest",
				OutputTypeName: "CreateThingResponse",
				StreamType:     streamTypeUnary,
				Idempotent:     false,
			},
		},
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, data); err != nil {
		t.Fatalf("failed to execute template: %v", err)
	}

	output := buf.String()

	// The idempotent method should get both a POST and a GET route.
	if !strings.Contains(output, `post<ExampleServiceHandlerInterface.Procedures.GetThing, GetThingRequest>`) {
		t.Error("expected POST route for idempotent method GetThing")
	}
	if !strings.Contains(output, `get<ExampleServiceHandlerInterface.Procedures.GetThing>`) {
		t.Error("expected GET route for idempotent method GetThing")
	}
	if !strings.Contains(output, `handleGet<ExampleServiceHandlerInterface.Procedures.GetThing, GetThingRequest, GetThingResponse>`) {
		t.Error("expected handleGet call for idempotent method GetThing")
	}

	// The non-idempotent method should only get a POST route, no GET route.
	if !strings.Contains(output, `post<ExampleServiceHandlerInterface.Procedures.CreateThing, CreateThingRequest>`) {
		t.Error("expected POST route for non-idempotent method CreateThing")
	}
	if strings.Contains(output, `get<ExampleServiceHandlerInterface.Procedures.CreateThing>`) {
		t.Error("unexpected GET route for non-idempotent method CreateThing")
	}

	// Imports: get and handleGet should be present when there is at least one idempotent method.
	if !strings.Contains(output, `import io.ktor.server.resources.get`) {
		t.Error("expected 'import io.ktor.server.resources.get' in output")
	}
	if !strings.Contains(output, `import io.github.ichizero.connect.ktor.handleGet`) {
		t.Error("expected 'import io.github.ichizero.connect.ktor.handleGet' in output")
	}
}

func Test_template_no_idempotent_methods_still_compiles(t *testing.T) {
	t.Parallel()

	tmpl, err := loadTemplate()
	if err != nil {
		t.Fatalf("failed to load template: %v", err)
	}

	data := &serviceData{
		ProtoPackageName: "example.v1",
		JavaPackageName:  "com.example.v1",
		SourceFileName:   "example/v1/service.proto",
		Name:             "MutatingService",
		Methods: []*methodData{
			{
				Name:           "Create",
				InputTypeName:  "CreateRequest",
				OutputTypeName: "CreateResponse",
				StreamType:     streamTypeUnary,
				Idempotent:     false,
			},
		},
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, data); err != nil {
		t.Fatalf("failed to execute template: %v", err)
	}

	output := buf.String()

	// No GET route should be present for non-idempotent methods.
	if strings.Contains(output, `get<`) {
		t.Error("unexpected GET route in output for non-idempotent-only service")
	}

	// GET-related imports must not be emitted when no idempotent method exists,
	// otherwise the generated Kotlin file fails to compile with "unused import" warnings.
	if strings.Contains(output, `import io.ktor.server.resources.get`) {
		t.Error("unexpected 'import io.ktor.server.resources.get' for non-idempotent-only service")
	}
	if strings.Contains(output, `import io.github.ichizero.connect.ktor.handleGet`) {
		t.Error("unexpected 'import io.github.ichizero.connect.ktor.handleGet' for non-idempotent-only service")
	}
}
