//go:build !linux

package metrics

import (
	"context"
	"errors"

	"remotestatus/internal/model"
)

type Collector struct{}

func New() *Collector { return &Collector{} }

func (*Collector) Collect(_ context.Context, _ string) (model.Snapshot, error) {
	return model.Snapshot{}, errors.New("the agent collector only supports Linux")
}
