package protogenhelper

import (
	"fmt"
	"io"
	"os"

	"google.golang.org/protobuf/compiler/protogen"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/pluginpb"
)

func Run(opts *protogen.Options, generator func(*protogen.Plugin) error) error {
	if len(os.Args) > 1 {
		return fmt.Errorf("unknown argument %q", os.Args[1])
	}

	in, err := io.ReadAll(os.Stdin)
	if err != nil {
		return err
	}

	req := &pluginpb.CodeGeneratorRequest{}
	if err := proto.Unmarshal(in, req); err != nil {
		return err
	}

	setDummyGoPackage(req)

	p, err := opts.New(req)
	if err != nil {
		return err
	}
	if err := generator(p); err != nil {
		p.Error(err)
	}

	out, err := proto.Marshal(p.Response())
	if err != nil {
		return err
	}

	if _, err := os.Stdout.Write(out); err != nil {
		return err
	}
	return nil
}

// setDummyGoPackage sets dummy go_package file option to avoid package name check error on [*protogen.Options].New.
func setDummyGoPackage(req *pluginpb.CodeGeneratorRequest) {
	const dummyGoPackage = "com.dummy"

	for _, file := range req.GetProtoFile() {
		if file.GetOptions().GetGoPackage() == "" {
			pkg := dummyGoPackage
			opts := file.GetOptions()
			if opts != nil {
				opts.GoPackage = &pkg
			}
		}
	}
}
