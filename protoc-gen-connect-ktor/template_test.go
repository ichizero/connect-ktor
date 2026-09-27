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

func Test_template_no_side_effects_method_emits_get_route(t *testing.T) {
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
		HasGetRoute:      true,
		Methods: []*methodData{
			{
				Name:           "GetThing",
				InputTypeName:  "GetThingRequest",
				OutputTypeName: "GetThingResponse",
				StreamType:     streamTypeUnary,
				NoSideEffects:  true,
			},
			{
				Name:           "CreateThing",
				InputTypeName:  "CreateThingRequest",
				OutputTypeName: "CreateThingResponse",
				StreamType:     streamTypeUnary,
				NoSideEffects:  false,
			},
		},
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, data); err != nil {
		t.Fatalf("failed to execute template: %v", err)
	}

	output := buf.String()

	// The NO_SIDE_EFFECTS method should get both a POST and a GET route.
	if !strings.Contains(output, `post<ExampleServiceHandlerInterface.Procedures.GetThing, GetThingRequest>`) {
		t.Error("expected POST route for no-side-effects method GetThing")
	}
	if !strings.Contains(output, `get<ExampleServiceHandlerInterface.Procedures.GetThing>`) {
		t.Error("expected GET route for no-side-effects method GetThing")
	}
	if !strings.Contains(output, `handleGet<ExampleServiceHandlerInterface.Procedures.GetThing, GetThingRequest, GetThingResponse>`) {
		t.Error("expected handleGet call for no-side-effects method GetThing")
	}

	// A method without NO_SIDE_EFFECTS should only get a POST route, no GET route.
	if !strings.Contains(output, `post<ExampleServiceHandlerInterface.Procedures.CreateThing, CreateThingRequest>`) {
		t.Error("expected POST route for method CreateThing")
	}
	if strings.Contains(output, `get<ExampleServiceHandlerInterface.Procedures.CreateThing>`) {
		t.Error("unexpected GET route for method CreateThing without NO_SIDE_EFFECTS")
	}

	// Imports: get and handleGet should be present when there is at least one GET route.
	if !strings.Contains(output, `import io.ktor.server.resources.get`) {
		t.Error("expected 'import io.ktor.server.resources.get' in output")
	}
	if !strings.Contains(output, `import io.github.ichizero.connect.ktor.handleGet`) {
		t.Error("expected 'import io.github.ichizero.connect.ktor.handleGet' in output")
	}
}

func Test_template_no_get_routes_still_compiles(t *testing.T) {
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
				NoSideEffects:  false,
			},
		},
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, data); err != nil {
		t.Fatalf("failed to execute template: %v", err)
	}

	output := buf.String()

	// No GET route should be present when no method is NO_SIDE_EFFECTS.
	if strings.Contains(output, `get<`) {
		t.Error("unexpected GET route in output for a service without NO_SIDE_EFFECTS methods")
	}

	// GET-related imports must not be emitted when no GET route exists,
	// otherwise the generated Kotlin file fails to compile with "unused import" warnings.
	if strings.Contains(output, `import io.ktor.server.resources.get`) {
		t.Error("unexpected 'import io.ktor.server.resources.get' for a service without GET routes")
	}
	if strings.Contains(output, `import io.github.ichizero.connect.ktor.handleGet`) {
		t.Error("unexpected 'import io.github.ichizero.connect.ktor.handleGet' for a service without GET routes")
	}
}
