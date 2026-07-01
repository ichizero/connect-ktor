package generator

import (
	"bytes"
	"strings"
	"testing"

	"google.golang.org/protobuf/compiler/protogen"
	"google.golang.org/protobuf/types/descriptorpb"
)

func Test_toKDocComment(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name     string
		comments protogen.Comments
		want     string
	}{
		{
			name:     "empty comment",
			comments: "",
			want:     "",
		},
		{
			name:     "single line",
			comments: "foo\n",
			want:     "/**\n * foo\n */\n",
		},
		{
			name:     "multiple lines",
			comments: "foo\nbar\n",
			want:     "/**\n * foo\n * bar\n */\n",
		},
		{
			name:     "without trailing newline",
			comments: "foo",
			want:     "/**\n * foo\n */\n",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()

			if got := toKDocComment(tt.comments); got != tt.want {
				t.Errorf("toKDocComment() = %q, want %q", got, tt.want)
			}
		})
	}
}

func Test_classifyStreamType(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name           string
		clientStream   bool
		serverStream   bool
		wantType       streamType
		wantSupported  bool
		describesShape string
	}{
		{
			name:           "unary",
			clientStream:   false,
			serverStream:   false,
			wantType:       streamTypeUnary,
			wantSupported:  true,
			describesShape: "Req -> Res",
		},
		{
			name:           "client streaming",
			clientStream:   true,
			serverStream:   false,
			wantType:       streamTypeClient,
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
			st, ok := classifyStreamType(tt.clientStream, tt.serverStream)
			if ok != tt.wantSupported {
				t.Errorf("classifyStreamType(%v, %v) supported = %v, want %v (%s)",
					tt.clientStream, tt.serverStream, ok, tt.wantSupported, tt.describesShape)
			}
			if ok && st != tt.wantType {
				t.Errorf("classifyStreamType(%v, %v) type = %q, want %q",
					tt.clientStream, tt.serverStream, st, tt.wantType)
			}
		})
	}
}

// Test_idempotencyAllowsGet pins down that only NO_SIDE_EFFECTS is eligible for a Connect
// GET route. IDEMPOTENT permits safe retries but still allows side effects, so it must not
// be exposed over GET; IDEMPOTENCY_UNKNOWN (the default) is likewise excluded.
func Test_idempotencyAllowsGet(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name  string
		level descriptorpb.MethodOptions_IdempotencyLevel
		want  bool
	}{
		{"unknown (default)", descriptorpb.MethodOptions_IDEMPOTENCY_UNKNOWN, false},
		{"idempotent (has side effects)", descriptorpb.MethodOptions_IDEMPOTENT, false},
		{"no side effects", descriptorpb.MethodOptions_NO_SIDE_EFFECTS, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			if got := idempotencyAllowsGet(tt.level); got != tt.want {
				t.Errorf("idempotencyAllowsGet(%v) = %v, want %v", tt.level, got, tt.want)
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
			{Name: "Say", InputTypeName: "SayRequest", OutputTypeName: "SayResponse", StreamType: streamTypeUnary},
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
			{Name: "Say", InputTypeName: "SayRequest", OutputTypeName: "SayResponse", StreamType: streamTypeUnary},
			{Name: "Upload", InputTypeName: "UploadRequest", OutputTypeName: "UploadResponse", StreamType: streamTypeClient},
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
