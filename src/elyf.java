import java.net.ConnectException;
import java.net.UnknownHostException;

/** Vanilla/FML stream connection for the separately reconstructed local server. */
public class elyf extends Thread {
    public final String _a;
    public final int _b;
    public final nwsu _c;

    public elyf(nwsu screen, String host, int port) {
        this._c = screen;
        this._a = host;
        this._b = port;
    }

    public void run() {
        try {
            nwsu._a(_c, new oiuh(nwsu._a(_c), _a, _b, nwsu._e(_c)));
            if (nwsu._b(_c)) return;
            nwsu._d(_c).func_72552_c(new lrtj(78, nwsu._c(_c)._T()._a(), _a, _b));
        } catch (UnknownHostException error) {
            fail("Unknown host '" + _a + "'");
        } catch (ConnectException error) {
            fail(error.getMessage());
        } catch (Exception error) {
            error.printStackTrace();
            fail(error.toString());
        }
    }

    private void fail(String message) {
        if (!nwsu._b(_c)) {
            nwsu._f(_c)._a(new plrq(nwsu._e(_c), "connect.failed", "disconnect.genericReason", message));
        }
    }
}
