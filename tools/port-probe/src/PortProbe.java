package probe;
public final class PortProbe implements net.fabricmc.api.ClientModInitializer {
 public void onInitializeClient() { switch(System.getProperty("port.probe","flat")) {
  case "flat" -> new FlatBaseProbe().onInitializeClient();
  case "font" -> new FontProbe().onInitializeClient();
  case "ui" -> new CombatUiProbe().onInitializeClient();
  case "mace" -> new CombatProbe().onInitializeClient();
  default -> throw new IllegalArgumentException("Unknown probe");
 } }
}