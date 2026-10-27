package go;

import android.content.Context;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.logging.Logger;

/* loaded from: classes.dex */
public class Seq {
    static final RefTracker tracker;
    private static Logger log = Logger.getLogger("GoSeq");
    private static final int NULL_REFNUM = 41;
    public static final Ref nullRef = new Ref(NULL_REFNUM, null);
    private static final GoRefQueue goRefQueue = new GoRefQueue();

    public interface GoObject {
        int incRefnum();
    }

    static class GoRef extends PhantomReference<GoObject> {
        final int refnum;

        GoRef(int i2, GoObject goObject, GoRefQueue goRefQueue) {
            super(goObject, goRefQueue);
            if (i2 <= 0) {
                this.refnum = i2;
                return;
            }
            throw new RuntimeException("GoRef instantiated with a Java refnum " + i2);
        }
    }

    static class GoRefQueue extends ReferenceQueue<GoObject> {
        private final Collection<GoRef> refs = Collections.synchronizedCollection(new HashSet());

        GoRefQueue() {
            Thread thread = new Thread(new Runnable() { // from class: go.Seq.GoRefQueue.1
                @Override // java.lang.Runnable
                public void run() {
                    while (true) {
                        try {
                            GoRef goRef = (GoRef) GoRefQueue.this.remove();
                            GoRefQueue.this.refs.remove(goRef);
                            Seq.destroyRef(goRef.refnum);
                            goRef.clear();
                        } catch (InterruptedException unused) {
                        }
                    }
                }
            });
            thread.setDaemon(true);
            thread.setName("GoRefQueue Finalizer Thread");
            thread.start();
        }

        void track(int i2, GoObject goObject) {
            this.refs.add(new GoRef(i2, goObject, this));
        }
    }

    public interface Proxy extends GoObject {
    }

    public static final class Ref {
        public final Object obj;
        private int refcnt;
        public final int refnum;

        Ref(int i2, Object obj) {
            if (i2 >= 0) {
                this.refnum = i2;
                this.refcnt = 0;
                this.obj = obj;
            } else {
                throw new RuntimeException("Ref instantiated with a Go refnum " + i2);
            }
        }

        static /* synthetic */ int access$110(Ref ref) {
            int i2 = ref.refcnt;
            ref.refcnt = i2 - 1;
            return i2;
        }

        void inc() {
            int i2 = this.refcnt;
            if (i2 != Integer.MAX_VALUE) {
                this.refcnt = i2 + 1;
                return;
            }
            throw new RuntimeException("refnum " + this.refnum + " overflow");
        }
    }

    static final class RefMap {
        private int next = 0;
        private int live = 0;
        private int[] keys = new int[16];
        private Ref[] objs = new Ref[16];

        RefMap() {
        }

        private void grow() {
            Ref[] refArr;
            int iRoundPow2 = roundPow2(this.live) * 2;
            int[] iArr = this.keys;
            if (iRoundPow2 > iArr.length) {
                iArr = new int[iArr.length * 2];
                refArr = new Ref[this.objs.length * 2];
            } else {
                refArr = this.objs;
            }
            int i2 = 0;
            int i3 = 0;
            while (true) {
                int[] iArr2 = this.keys;
                if (i2 >= iArr2.length) {
                    break;
                }
                Ref ref = this.objs[i2];
                if (ref != null) {
                    iArr[i3] = iArr2[i2];
                    refArr[i3] = ref;
                    i3++;
                }
                i2++;
            }
            for (int i4 = i3; i4 < iArr.length; i4++) {
                iArr[i4] = 0;
                refArr[i4] = null;
            }
            this.keys = iArr;
            this.objs = refArr;
            this.next = i3;
            if (this.live == i3) {
                return;
            }
            throw new RuntimeException("bad state: live=" + this.live + ", next=" + this.next);
        }

        private static int roundPow2(int i2) {
            int i3 = 1;
            while (i3 < i2) {
                i3 *= 2;
            }
            return i3;
        }

        Ref get(int i2) {
            int iBinarySearch = Arrays.binarySearch(this.keys, 0, this.next, i2);
            if (iBinarySearch >= 0) {
                return this.objs[iBinarySearch];
            }
            return null;
        }

        void put(int i2, Ref ref) {
            if (ref == null) {
                throw new RuntimeException("put a null ref (with key " + i2 + ")");
            }
            int iBinarySearch = Arrays.binarySearch(this.keys, 0, this.next, i2);
            if (iBinarySearch >= 0) {
                Ref[] refArr = this.objs;
                if (refArr[iBinarySearch] == null) {
                    refArr[iBinarySearch] = ref;
                    this.live++;
                }
                if (refArr[iBinarySearch] == ref) {
                    return;
                }
                throw new RuntimeException("replacing an existing ref (with key " + i2 + ")");
            }
            if (this.next >= this.keys.length) {
                grow();
                iBinarySearch = Arrays.binarySearch(this.keys, 0, this.next, i2);
            }
            int i3 = iBinarySearch ^ (-1);
            int i4 = this.next;
            if (i3 < i4) {
                int[] iArr = this.keys;
                int i5 = i3 + 1;
                System.arraycopy(iArr, i3, iArr, i5, i4 - i3);
                Ref[] refArr2 = this.objs;
                System.arraycopy(refArr2, i3, refArr2, i5, this.next - i3);
            }
            this.keys[i3] = i2;
            this.objs[i3] = ref;
            this.live++;
            this.next++;
        }

        void remove(int i2) {
            int iBinarySearch = Arrays.binarySearch(this.keys, 0, this.next, i2);
            if (iBinarySearch >= 0) {
                Ref[] refArr = this.objs;
                if (refArr[iBinarySearch] != null) {
                    refArr[iBinarySearch] = null;
                    this.live--;
                }
            }
        }
    }

    static final class RefTracker {
        private static final int REF_OFFSET = 42;
        private int next = REF_OFFSET;
        private final RefMap javaObjs = new RefMap();
        private final IdentityHashMap<Object, Integer> javaRefs = new IdentityHashMap<>();

        RefTracker() {
        }

        synchronized void dec(int i2) {
            if (i2 <= 0) {
                Seq.log.severe("dec request for Go object " + i2);
                return;
            }
            if (i2 == Seq.nullRef.refnum) {
                return;
            }
            Ref ref = this.javaObjs.get(i2);
            if (ref == null) {
                throw new RuntimeException("referenced Java object is not found: refnum=" + i2);
            }
            Ref.access$110(ref);
            if (ref.refcnt <= 0) {
                this.javaObjs.remove(i2);
                this.javaRefs.remove(ref.obj);
            }
        }

        synchronized Ref get(int i2) {
            if (i2 < 0) {
                throw new RuntimeException("ref called with Go refnum " + i2);
            }
            if (i2 == Seq.NULL_REFNUM) {
                return Seq.nullRef;
            }
            Ref ref = this.javaObjs.get(i2);
            if (ref != null) {
                return ref;
            }
            throw new RuntimeException("unknown java Ref: " + i2);
        }

        synchronized int inc(Object obj) {
            if (obj == null) {
                return Seq.NULL_REFNUM;
            }
            if (obj instanceof Proxy) {
                return ((Proxy) obj).incRefnum();
            }
            Integer numValueOf = this.javaRefs.get(obj);
            if (numValueOf == null) {
                int i2 = this.next;
                if (i2 == Integer.MAX_VALUE) {
                    throw new RuntimeException("createRef overflow for " + obj);
                }
                this.next = i2 + 1;
                numValueOf = Integer.valueOf(i2);
                this.javaRefs.put(obj, numValueOf);
            }
            int iIntValue = numValueOf.intValue();
            Ref ref = this.javaObjs.get(iIntValue);
            if (ref == null) {
                ref = new Ref(iIntValue, obj);
                this.javaObjs.put(iIntValue, ref);
            }
            ref.inc();
            return iIntValue;
        }

        synchronized void incRefnum(int i2) {
            Ref ref = this.javaObjs.get(i2);
            if (ref == null) {
                throw new RuntimeException("referenced Java object is not found: refnum=" + i2);
            }
            ref.inc();
        }
    }

    static {
        System.loadLibrary("gojni");
        init();
        Universe.touch();
        tracker = new RefTracker();
    }

    private Seq() {
    }

    static void decRef(int i2) {
        tracker.dec(i2);
    }

    static native void destroyRef(int i2);

    public static Ref getRef(int i2) {
        return tracker.get(i2);
    }

    public static int incGoObjectRef(GoObject goObject) {
        return goObject.incRefnum();
    }

    public static native void incGoRef(int i2, GoObject goObject);

    public static int incRef(Object obj) {
        return tracker.inc(obj);
    }

    public static void incRefnum(int i2) {
        tracker.incRefnum(i2);
    }

    private static native void init();

    public static void setContext(Context context) {
        setContext((Object) context);
    }

    static native void setContext(Object obj);

    public static void touch() {
    }

    public static void trackGoRef(int i2, GoObject goObject) {
        if (i2 <= 0) {
            goRefQueue.track(i2, goObject);
            return;
        }
        throw new RuntimeException("trackGoRef called with Java refnum " + i2);
    }
}
