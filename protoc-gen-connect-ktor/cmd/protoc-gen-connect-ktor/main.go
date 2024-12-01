package main

import (
	"log/slog"
	"os"

	"google.golang.org/protobuf/compiler/protogen"

	generator "github.com/ichizero/connect-ktor/protoc-gen-connect-ktor"
	"github.com/ichizero/connect-ktor/protoc-gen-connect-ktor/internal/protogenhelper"
)

func main() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{})))

	if err := protogenhelper.Run(&protogen.Options{}, generator.Run); err != nil {
		slog.Error("unexpected error", slog.Any("error", err))
		os.Exit(1)
	}
}
