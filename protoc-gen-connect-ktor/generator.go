package generator

import (
	"fmt"
	"strings"

	"google.golang.org/protobuf/compiler/protogen"
	descriptorpb "google.golang.org/protobuf/types/descriptorpb"
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
	hasIdempotent := false
	for _, method := range service.Methods {
		if method.Desc.IsStreamingServer() || method.Desc.IsStreamingClient() {
			continue
		}

		inputTypeName := method.Input.GoIdent.GoName
		outputTypeName := method.Output.GoIdent.GoName
		idempotent := method.Desc.Options().(*descriptorpb.MethodOptions).GetIdempotencyLevel() == descriptorpb.MethodOptions_NO_SIDE_EFFECTS
		if idempotent {
			hasIdempotent = true
		}
		methods = append(methods, &methodData{
			Name:           string(method.Desc.Name()),
			Comment:        toKDocComment(method.Comments.Leading),
			InputTypeName:  inputTypeName,
			OutputTypeName: outputTypeName,
			Idempotent:     idempotent,
		})
	}

	return &serviceData{
		ProtoPackageName: protoPackageName,
		JavaPackageName:  javaPackageName,
		SourceFileName:   sourceFileName,
		Name:             string(service.Desc.Name()),
		Comment:          toKDocComment(service.Comments.Leading),
		Methods:          methods,
		HasIdempotent:    hasIdempotent,
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
