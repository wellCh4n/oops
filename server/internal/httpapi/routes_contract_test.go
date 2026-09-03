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

// The two OAuth endpoints are excluded from the coverage report because the
// suite cannot drive a browser through a real provider. That means "untested",
// not "unnecessary" — and reading it as the latter once already shipped a login
// page whose button hit a route that did not exist, answering 401 because the
// request fell through to the authenticated group.
//
// So the exclusion list is checked from the other side here: everything on it
// must still be mounted, and must still be public.
func TestExcludedEndpointsAreStillMountedAndPublic(t *testing.T) {
	mounted := map[string]Route{}
	server := &Server{}
	for _, route := range server.Routes() {
		mounted["/api"+pathVariable.ReplaceAllString(route.Pattern, "{}")] = route
	}
	for excluded := range excludedFromCoverage {
		key := pathVariable.ReplaceAllString(excluded, "{}")
		route, found := mounted[key]
		if !found {
			t.Errorf("%s is excluded from coverage but is not mounted at all", excluded)
			continue
		}
		if !route.Public {
			t.Errorf("%s must be public: the caller has no session yet", excluded)
		}
	}
}
