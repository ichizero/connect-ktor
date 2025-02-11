package tmplfunc

import "testing"

func Test_toLowerFirst(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name  string
		value string
		want  string
	}{
		{name: "empty string", value: "", want: ""},
		{name: "single character", value: "A", want: "a"},
		{name: "multiple characters", value: "Hello", want: "hello"},
		{name: "PascalCase", value: "FooBar", want: "fooBar"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()

			if got := toLowerFirst(tt.value); got != tt.want {
				t.Errorf("toLowerFirst() = %v, want %v", got, tt.want)
			}
		})
	}
}

func Test_indent(t *testing.T) {
	t.Parallel()

	type args struct {
		spaces int
		value  string
	}
	tests := []struct {
		name string
		args args
		want string
	}{
		{
			name: "no indentation",
			args: args{spaces: 0, value: "line1\nline2"},
			want: "line1\nline2",
		},
		{
			name: "indent with spaces",
			args: args{spaces: 4, value: "line1\nline2"},
			want: "    line1\n    line2",
		},
		{
			name: "indent with multiple lines",
			args: args{spaces: 2, value: "first line\nsecond line\nthird line"},
			want: "  first line\n  second line\n  third line",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()

			if got := indent(tt.args.spaces, tt.args.value); got != tt.want {
				t.Errorf("indent() = %v, want %v", got, tt.want)
			}
		})
	}
}

func Test_nIndent(t *testing.T) {
	t.Parallel()

	type args struct {
		spaces int
		value  string
	}
	tests := []struct {
		name string
		args args
		want string
	}{
		{
			name: "no indentation",
			args: args{spaces: 0, value: "line1\nline2"},
			want: "\nline1\nline2",
		},
		{
			name: "indent with spaces",
			args: args{spaces: 4, value: "line1\nline2"},
			want: "\n    line1\n    line2",
		},
		{
			name: "indent with multiple lines",
			args: args{spaces: 2, value: "first line\nsecond line\nthird line"},
			want: "\n  first line\n  second line\n  third line",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()

			if got := nIndent(tt.args.spaces, tt.args.value); got != tt.want {
				t.Errorf("nIndent() = %v, want %v", got, tt.want)
			}
		})
	}
}
