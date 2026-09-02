package httpapi

import (
	"encoding/json"
	"os"
	"testing"
)

// contractPath is the inventory tests/integration/test_zz_coverage.py judges the
// run against. The table and the contract are checked against each other here so
// a missing or misspelled route fails in seconds rather than after a full
// integration run.
const contractPath = "../../../tests/integration/routes.json"

func TestRouteTableMatchesTheIntegrationContract(t *testing.T) {
	raw, err := os.ReadFile(contractPath)
	if err != nil {
		t.Fatal(err)
	}
	var expected []RouteInfo
	if err := json.Unmarshal(raw, &expected); err != nil {
		t.Fatal(err)
	}
	server := &Server{}
	actual := RouteTable(server.Routes())
	want := map[string]string{}
	for _, info := range expected {
		want[info.Key] = info.Controller
	}
	got := map[string]string{}
	for _, info := range actual {
		got[info.Key] = info.Controller
	}
	for key, controller := range want {
		if gotController, found := got[key]; !found {
			t.Errorf("MISSING  %s (%s)", key, controller)
		} else if gotController != controller {
			t.Errorf("WRONG-CTL %s: got %s want %s", key, gotController, controller)
		}
	}
	for key := range got {
		if _, found := want[key]; !found {
			t.Errorf("EXTRA    %s", key)
		}
	}
	t.Logf("contract %d routes, table %d routes", len(want), len(got))
}
