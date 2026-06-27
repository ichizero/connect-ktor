// Package generator implements the protoc plugin entry point that emits
// Ktor route handler interfaces from Protocol Buffers service definitions.
package generator

import (
	"fmt"
	"strings"

	"google.golang.org/protobuf/compiler/protogen"
)

// streamType mirrors Connect's StreamType vocabulary (see connect-go's connect.StreamType and
// connect-kotlin's com.connectrpc.StreamType) for the RPC shapes connect-ktor currently knows
// how to generate. Server-streaming and bidirectional streaming are reserved for future work;
// methods of those kinds are skipped during generation.
type streamType string

const (
	streamTypeUnary  streamType = "Unary"
	streamTypeClient streamType = "Client"
)

// Run is the protogen entry point: it iterates the files marked for
// generation by protoc and emits one Ktor handler file per input.
func Run(plugin *protogen.Plugin) error {
	for _, file := range plugin.Files {
		if !file.Generate {
			continue
		}

		if err := run(plugin, file); err != nil {
			return err
		}
	}

	return nil
}

func run(plugin *protogen.Plugin, file *protogen.File) error {
	for _, service := range file.Services {
		tmpl, err := loadTemplate()
		if err != nil {
			return fmt.Errorf("failed to load template: %w", err)
		}

		javaPackage := file.Proto.GetOptions().GetJavaPackage()
		filePath := strings.ReplaceAll(javaPackage, ".", "/")

		filename := fmt.Sprintf("%s/%sHandlerInterface.kt", filePath, service.Desc.Name())
		g := plugin.NewGeneratedFile(filename, "")
		data := serviceToData(service, file.Proto.GetPackage(), file.Proto.GetOptions().GetJavaPackage(), file.Desc.Path())

		if err := tmpl.Execute(g, data); err != nil {
			return fmt.Errorf("failed to execute template: %w", err)
		}
	}

	return nil
}

func serviceToData(service *protogen.Service, protoPackageName, javaPackageName, sourceFileName string) *serviceData {
	methods := make([]*methodData, 0, len(service.Methods))
	hasClientStream := false
	for _, method := range service.Methods {
		st, ok := streamTypeOf(method)
		if !ok {
			// Server-streaming / bidirectional streaming: skip for now.
			continue
		}
		if st == streamTypeClient {
			hasClientStream = true
		}
		methods = append(methods, &methodData{
			Name:           string(method.Desc.Name()),
			Comment:        toKDocComment(method.Comments.Leading),
			InputTypeName:  method.Input.GoIdent.GoName,
			OutputTypeName: method.Output.GoIdent.GoName,
			StreamType:     st,
		})
	}

	return &serviceData{
		ProtoPackageName: protoPackageName,
		JavaPackageName:  javaPackageName,
		SourceFileName:   sourceFileName,
		Name:             string(service.Desc.Name()),
		Comment:          toKDocComment(service.Comments.Leading),
		Methods:          methods,
		HasClientStream:  hasClientStream,
	}
}

// streamTypeOf returns the supported stream type and true, or (_, false) when the method is a
// server-streaming or bidirectional RPC that we do not yet generate code for.
func streamTypeOf(method *protogen.Method) (streamType, bool) {
	return classifyStreamType(method.Desc.IsStreamingClient(), method.Desc.IsStreamingServer())
}

// classifyStreamType is the pure-boolean form of [streamTypeOf], split out so it can be
// table-driven tested without constructing protogen descriptors.
func classifyStreamType(clientStream, serverStream bool) (streamType, bool) {
	switch {
	case !clientStream && !serverStream:
		return streamTypeUnary, true
	case clientStream && !serverStream:
		return streamTypeClient, true
	default:
		return "", false
	}
}

func toKDocComment(comments protogen.Comments) string {
	if comments == "" {
		return ""
	}

	c := string(comments)

	b := strings.Builder{}
	b.Grow(len(c))

	b.WriteString("/**\n")
	for line := range strings.SplitSeq(strings.TrimSuffix(c, "\n"), "\n") {
		b.WriteString(" * ")
		b.WriteString(line)
		b.WriteString("\n")
	}
	b.WriteString(" */\n")

	return b.String()
}
