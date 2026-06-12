package generator

import (
	"fmt"
	"strings"

	"google.golang.org/protobuf/compiler/protogen"
)

// methodKind enumerates the RPC shapes connect-ktor currently knows how to generate.
// Server-streaming and bidirectional streaming are reserved for future work; methods of
// those kinds are skipped during generation.
type methodKind string

const (
	methodKindUnary        methodKind = "Unary"
	methodKindClientStream methodKind = "ClientStream"
)

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
			return fmt.Errorf("failed to load template: %v", err)
		}

		javaPackage := file.Proto.GetOptions().GetJavaPackage()
		filePath := strings.ReplaceAll(javaPackage, ".", "/")

		filename := fmt.Sprintf("%s/%sHandlerInterface.kt", filePath, service.Desc.Name())
		g := plugin.NewGeneratedFile(filename, "")
		data := serviceToData(service, file.Proto.GetPackage(), file.Proto.GetOptions().GetJavaPackage(), file.Desc.Path())

		if err := tmpl.Execute(g, data); err != nil {
			return fmt.Errorf("failed to execute template: %v", err)
		}
	}

	return nil
}

func serviceToData(service *protogen.Service, protoPackageName, javaPackageName, sourceFileName string) *serviceData {
	methods := make([]*methodData, 0, len(service.Methods))
	hasClientStream := false
	for _, method := range service.Methods {
		kind, ok := classifyMethod(method)
		if !ok {
			// Server-streaming / bidirectional streaming: skip for now.
			continue
		}
		if kind == methodKindClientStream {
			hasClientStream = true
		}
		methods = append(methods, &methodData{
			Name:           string(method.Desc.Name()),
			Comment:        toKDocComment(method.Comments.Leading),
			InputTypeName:  method.Input.GoIdent.GoName,
			OutputTypeName: method.Output.GoIdent.GoName,
			Kind:           kind,
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

// classifyMethod returns the supported method kind and true, or (_, false) when the
// method is a server-streaming or bidirectional RPC that we do not yet generate code for.
func classifyMethod(method *protogen.Method) (methodKind, bool) {
	return classifyStreamKind(method.Desc.IsStreamingClient(), method.Desc.IsStreamingServer())
}

// classifyStreamKind is the pure-boolean form of [classifyMethod], split out so it can be
// table-driven tested without constructing protogen descriptors.
func classifyStreamKind(clientStream, serverStream bool) (methodKind, bool) {
	switch {
	case !clientStream && !serverStream:
		return methodKindUnary, true
	case clientStream && !serverStream:
		return methodKindClientStream, true
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
	for _, line := range strings.Split(strings.TrimSuffix(c, "\n"), "\n") {
		b.WriteString(" * ")
		b.WriteString(line)
		b.WriteString("\n")
	}
	b.WriteString(" */\n")

	return b.String()
}
