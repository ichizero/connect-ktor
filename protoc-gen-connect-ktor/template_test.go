package generator

import (
	"testing"
)

func Test_loadTemplate(t *testing.T) {
	t.Parallel()

	if _, err := loadTemplate(); err != nil {
		t.Errorf("failed to load template: %v", err)
	}
}
