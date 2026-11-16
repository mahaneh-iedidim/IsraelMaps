package mp;

import javax.microedition.location.Criteria;
import javax.microedition.location.Location;
import javax.microedition.location.LocationListener;
import javax.microedition.location.LocationProvider;
import javax.microedition.location.QualifiedCoordinates;

/** JSR-179 location (built-in GPS); only touched after Ui has checked that the API exists */
final class Gps implements LocationListener {
    final Ui ui;
    LocationProvider lp;

    Gps(Ui ui) {
        this.ui = ui;
    }

    /** starts the updates; false when the phone has no usable location provider */
    boolean start() {
        try {
            Criteria c = new Criteria();
            c.setHorizontalAccuracy(50);
            c.setCostAllowed(false);
            lp = LocationProvider.getInstance(c);
            if (lp == null) lp = LocationProvider.getInstance(null);
            if (lp == null) return false;
            lp.setLocationListener(this, 2, -1, -1);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    void stop() {
        try {
            if (lp != null) lp.setLocationListener(null, -1, -1, -1);
        } catch (Throwable t) {
        }
        lp = null;
    }

    public void locationUpdated(LocationProvider p, Location l) {
        try {
            if (l == null || !l.isValid()) return;
            QualifiedCoordinates q = l.getQualifiedCoordinates();
            if (q == null) return;
            int la = (int) (q.getLatitude() * 1000000.0);
            int lo = (int) (q.getLongitude() * 1000000.0);
            ui.gpsFix(la, lo, (int) q.getHorizontalAccuracy());
        } catch (Throwable t) {
        }
    }

    public void providerStateChanged(LocationProvider p, int state) {
        ui.gpsState(state == LocationProvider.AVAILABLE);
    }
}
