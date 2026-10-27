package go;

import go.Seq;

/* loaded from: classes.dex */
public abstract class Universe {

    private static final class proxyerror extends Exception implements Seq.Proxy, error {
        private final int refnum;

        proxyerror(int i2) {
            this.refnum = i2;
            Seq.trackGoRef(i2, this);
        }

        @Override // go.error
        public native String error();

        @Override // java.lang.Throwable
        public String getMessage() {
            return error();
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }
    }

    static {
        Seq.touch();
        _init();
    }

    private Universe() {
    }

    private static native void _init();

    public static void touch() {
    }
}
