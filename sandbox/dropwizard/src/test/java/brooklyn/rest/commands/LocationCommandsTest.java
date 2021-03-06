package brooklyn.rest.commands;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import org.testng.annotations.Test;

import brooklyn.rest.commands.locations.AddLocationCommand;
import brooklyn.rest.commands.locations.ListLocationsCommand;
import brooklyn.rest.resources.LocationResource;

public class LocationCommandsTest extends BrooklynCommandTest {

  @Override
  protected void setUpResources() throws Exception {
    addResource(new LocationResource());
  }

  @Test
  public void testListLocations() throws Exception {
    runCommandWithArgs(ListLocationsCommand.class);

    assertThat(standardOut(), containsString("localhost"));
  }

  @Test
  public void testAddLocation() throws Exception {
    runCommandWithArgs(AddLocationCommand.class,
        createTemporaryFileWithContent(".json", "{\"provider\":\"localhost\", \"config\":{}}"));

    assertThat(standardOut(), containsString("http://localhost:8080/v1/locations/1"));
  }
}
