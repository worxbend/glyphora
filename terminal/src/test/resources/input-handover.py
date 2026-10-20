"""Real controlling-PTY driver for NativeInputHandoverProbe (stdlib only)."""
import os
import pty
import re
import select
import signal
import sys
import time

pid, master = pty.fork()
if pid == 0:
    os.environ["TERM"] = "xterm-256color"
    os.execv(sys.argv[1], [sys.argv[1], "--enable-native-access=ALL-UNNAMED", "-cp", sys.argv[2],
                          "io.worxbend.tui.terminal.NativeInputHandoverProbe"])

output = bytearray()
seen = set()
deadline = time.monotonic() + 70
status = None
try:
    while time.monotonic() < deadline:
        ready, _, _ = select.select([master], [], [], 0.1)
        if ready:
            try:
                chunk = os.read(master, 65536)
            except OSError:
                break
            if not chunk:
                break
            output.extend(chunk)
            for match in re.finditer(rb"EDITOR_READY:(\d+)\r?\n", output):
                index = int(match.group(1))
                if index not in seen:
                    seen.add(index)
                    os.write(master, b"X")
        waited, polled_status = os.waitpid(pid, os.WNOHANG)
        if waited:
            status = polled_status
            break
    else:
        raise AssertionError("native probe exceeded its deadline")
    if status is None or not os.WIFEXITED(status):
        _, status = os.waitpid(pid, 0)
    assert os.waitstatus_to_exitcode(status) == 0, output.decode(errors="replace")
    assert seen == set(range(20)), seen
    assert b"PTY_HANDOVER_OK cycles=20" in output, output.decode(errors="replace")
    print("PTY_HANDOVER_OK cycles=20; all subprocess first bytes received; real JNI terminal")
except BaseException:
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    print(output.decode(errors="replace"), file=sys.stderr)
    raise
finally:
    os.close(master)
