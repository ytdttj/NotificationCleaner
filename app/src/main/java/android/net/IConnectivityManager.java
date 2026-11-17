package android.net;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Compile-only AIDL stub（island 分支：超级岛 xmsf 网络盲窗，islandplan.md §二）。
 * 运行时 android.* 由 boot classpath 提供真实 AIDL 实现（parent-first 委派），
 * 本文件仅保证编译期方法签名与平台 IConnectivityManager 的事务序一致。
 */
public interface IConnectivityManager extends IInterface {
    void setFirewallChainEnabled(int chain, boolean enable) throws RemoteException;

    void setUidFirewallRule(int chain, int uid, int rule) throws RemoteException;

    abstract class Stub extends Binder implements IConnectivityManager {
        public static IConnectivityManager asInterface(IBinder binder) {
            throw new UnsupportedOperationException("Compile-only stub");
        }
    }
}
