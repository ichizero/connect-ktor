package tmplfunc

import (
	"strings"
	"text/template"
)

var Map = template.FuncMap{
	"toLowerFirst": toLowerFirst,
	"indent":       indent,
	"nIndent":      nIndent,
}

func toLowerFirst(value string) string {
	if value == "" {
		return value
	}
	return strings.ToLower(value[:1]) + value[1:]
}

func indent(spaces int, value string) string {
	padding := strings.Repeat(" ", spaces)
	return padding + strings.Replace(value, "\n", "\n"+padding, -1)
}

func nIndent(spaces int, value string) string {
	return "\n" + indent(spaces, value)
}
