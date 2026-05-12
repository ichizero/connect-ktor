package generator

import (
	"bytes"
	"strings"
	"testing"
)

func Test_classifyStreamKind(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name           string
		clientStream   bool
		serverStream   bool
		wantKind       methodKind
		wantSupported  bool
		describesShape string
	}{
		{
			name:           "unary",
			clientStream:   false,
			serverStream:   false,
			wantKind:       methodKindUnary,
			wantSupported:  true,
			describesShape: "Req -> Res",
		},
		{
			name:           "client streaming",
			clientStream:   true,
			serverStream:   false,
			wantKind:       methodKindClientStream,
			wantSupported:  true,
			describesShape: "stream Req -> Res",
		},
		{
			name:           "server streaming (unsupported)",
			clientStream:   false,
			serverStream:   true,
			wantSupported:  false,
			describesShape: "Req -> stream Res",
		},
		{
			name:           "bidirectional (unsupported)",
			clientStream:   true,
			serverStream:   true,
			wantSupported:  false,
			describesShape: "stream Req -> stream Res",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			kind, ok := classifyStreamKind(tt.clientStream, tt.serverStream)
			if ok != tt.wantSupported {
				t.Errorf("classifyStreamKind(%v, %v) supported = %v, want %v (%s)",
					tt.clientStream, tt.serverStream, ok, tt.wantSupported, tt.describesShape)
			}
			if ok && kind != tt.wantKind {
				t.Errorf("classifyStreamKind(%v, %v) kind = %q, want %q",
					tt.clientStream, tt.serverStream, kind, tt.wantKind)
			}
		})
	}
}

// Test_template_unaryOnly verifies that a service with only unary methods produces output
// that compiles without the Flow import and without any handleClientStream call site —
// i.e. existing unary users see no churn.
func Test_template_unaryOnly(t *testing.T) {
	t.Parallel()
	out := renderTemplate(t, &serviceData{
		ProtoPackageName: "example.v1",
		JavaPackageName:  "com.example.v1",
		SourceFileName:   "example/v1/example.proto",
		Name:             "Example",
		Methods: []*methodData{
			{Name: "Say", InputTypeName: "SayRequest", OutputTypeName: "SayResponse", Kind: methodKindUnary},
		},
		HasClientStream: false,
	})

	mustContain(t, out, "interface ExampleHandlerInterface")
	mustContain(t, out, "suspend fun say(request: SayRequest, call: ApplicationCall): ResponseMessage<SayResponse>")
	mustContain(t, out, "post<ExampleHandlerInterface.Procedures.Say, SayRequest>(handle(handler::say))")
	mustNotContain(t, out, "kotlinx.coroutines.flow.Flow")
	mustNotContain(t, out, "handleClientStream")
}

// Test_template_mixedKinds verifies a service with both unary and client-streaming methods
// imports Flow/handleClientStream only when needed and emits the right dispatch.
func Test_template_mixedKinds(t *testing.T) {
	t.Parallel()
	out := renderTemplate(t, &serviceData{
		ProtoPackageName: "example.v1",
		JavaPackageName:  "com.example.v1",
		SourceFileName:   "example/v1/example.proto",
		Name:             "Example",
		Methods: []*methodData{
			{Name: "Say", InputTypeName: "SayRequest", OutputTypeName: "SayResponse", Kind: methodKindUnary},
			{Name: "Upload", InputTypeName: "UploadRequest", OutputTypeName: "UploadResponse", Kind: methodKindClientStream},
		},
		HasClientStream: true,
	})

	mustContain(t, out, "import kotlinx.coroutines.flow.Flow")
	mustContain(t, out, "import io.github.ichizero.connect.ktor.streaming.handleClientStream")
	mustContain(t, out, "suspend fun upload(requests: Flow<UploadRequest>, call: ApplicationCall): ResponseMessage<UploadResponse>")
	mustContain(t, out, "post<ExampleHandlerInterface.Procedures.Upload>(handleClientStream(handler::upload))")
	// unary entry remains unchanged.
	mustContain(t, out, "post<ExampleHandlerInterface.Procedures.Say, SayRequest>(handle(handler::say))")
}

func renderTemplate(t *testing.T, data *serviceData) string {
	t.Helper()
	tmpl, err := loadTemplate()
	if err != nil {
		t.Fatalf("loadTemplate: %v", err)
	}
	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, data); err != nil {
		t.Fatalf("Execute: %v", err)
	}
	return buf.String()
}

func mustContain(t *testing.T, haystack, needle string) {
	t.Helper()
	if !strings.Contains(haystack, needle) {
		t.Errorf("expected output to contain %q, got:\n%s", needle, haystack)
	}
}

func mustNotContain(t *testing.T, haystack, needle string) {
	t.Helper()
	if strings.Contains(haystack, needle) {
		t.Errorf("expected output NOT to contain %q, got:\n%s", needle, haystack)
	}
}
