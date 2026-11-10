import android.view.InputDevice;
import android.view.KeyEvent;
import java.util.Arrays;

public final class InputPowerOrigin {
  public static void main(String[] args) {
    for (int id : InputDevice.getDeviceIds()) {
      InputDevice d = InputDevice.getDevice(id);
      if (d != null && (d.getName().equals("virtual-remote") || d.getName().equals("virtual-remote-2"))) {
        System.out.println("name=" + d.getName() + " id=" + id + " descriptor=" + d.getDescriptor()
          + " vendor=" + d.getVendorId() + " product=" + d.getProductId()
          + " hasKeys_POWER_TVPOWER_SLEEP=" + Arrays.toString(d.hasKeys(KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_SLEEP)));
      }
    }
  }
}
