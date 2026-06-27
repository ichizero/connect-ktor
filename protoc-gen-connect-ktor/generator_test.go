package generator

import (
	"testing"

	"google.golang.org/protobuf/compiler/protogen"
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
