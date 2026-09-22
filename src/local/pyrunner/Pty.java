package local.pyrunner;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

final class Pty {
    static {
        System.loadLibrary("pty");
    }

    final FileInputStream in;
    final FileOutputStream out;
    private final int pid;
    private final int fd;

    private Pty(FileDescriptor descriptor, int pid, int fd) {
        this.in = new FileInputStream(descriptor);
        this.out = new FileOutputStream(descriptor);
        this.pid = pid;
        this.fd = fd;
    }

    static Pty start(String[] argv, String cwd, String[] env, int rows, int cols) {
        int[] pidOut = new int[2];
        FileDescriptor descriptor = createSubprocess(argv, cwd, env, rows, cols, pidOut);
        if (descriptor == null) {
            return null;
        }
        return new Pty(descriptor, pidOut[0], pidOut[1]);
    }

    int pid() {
        return pid;
    }

    int waitFor() {
        return waitForPid(pid);
    }

    void setWinSize(int rows, int cols) {
        setWinSize(fd, rows, cols);
    }

    void close() {
        try {
            in.close();
        } catch (IOException ignored) {
        }
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }

    private static native FileDescriptor createSubprocess(String[] argv, String cwd,
            String[] env, int rows, int cols, int[] pidOut);

    private static native void setWinSize(int fd, int rows, int cols);

    private static native int waitForPid(int pid);
}
