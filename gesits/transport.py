"""Byte transports to the bike (or to the simulator).

URIs:
  bt://AA:BB:CC:DD:EE:FF[/channel]   Bluetooth RFCOMM socket (Linux; Windows with Python 3.9+).
                                     Pair the bike first. Channel defaults to 1.
  serial:///dev/rfcomm0              Any serial port via pyserial (e.g. a Bluetooth COM port
  serial://COM5                      on Windows, or /dev/rfcomm0 from `rfcomm bind`).
  tcp://127.0.0.1:3333               The bundled simulator (python -m gesits sim).
"""
from __future__ import annotations

import socket
from urllib.parse import urlparse


class Transport:
    def write(self, data: bytes) -> None:
        raise NotImplementedError

    def read(self, timeout: float) -> bytes:
        """Return whatever arrives within `timeout` seconds (b"" if nothing)."""
        raise NotImplementedError

    def close(self) -> None:
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


class SocketTransport(Transport):
    def __init__(self, sock: socket.socket) -> None:
        self.sock = sock

    def write(self, data: bytes) -> None:
        self.sock.sendall(data)

    def read(self, timeout: float) -> bytes:
        self.sock.settimeout(timeout)
        try:
            data = self.sock.recv(4096)
        except (socket.timeout, TimeoutError):
            return b""
        if not data:
            raise ConnectionError("connection closed by peer")
        return data

    def close(self) -> None:
        self.sock.close()


class SerialTransport(Transport):
    def __init__(self, port: str, baudrate: int = 115200) -> None:
        try:
            import serial  # pyserial
        except ImportError as e:
            raise SystemExit("serial:// needs pyserial: pip install pyserial") from e
        self.ser = serial.Serial(port, baudrate=baudrate, timeout=0)

    def write(self, data: bytes) -> None:
        self.ser.write(data)
        self.ser.flush()

    def read(self, timeout: float) -> bytes:
        self.ser.timeout = timeout
        first = self.ser.read(1)
        if not first:
            return b""
        return first + self.ser.read(self.ser.in_waiting or 0)

    def close(self) -> None:
        self.ser.close()


def open_transport(uri: str, timeout: float = 10.0) -> Transport:
    u = urlparse(uri)
    if u.scheme == "bt":
        if not hasattr(socket, "AF_BLUETOOTH"):
            raise SystemExit("This Python has no Bluetooth socket support; use serial:// instead.")
        addr = u.netloc
        channel = int(u.path.strip("/") or 1)
        s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        s.settimeout(timeout)
        s.connect((addr, channel))
        return SocketTransport(s)
    if u.scheme == "serial":
        return SerialTransport((u.netloc + u.path) or u.path)
    if u.scheme == "tcp":
        s = socket.create_connection((u.hostname, u.port or 3333), timeout=timeout)
        return SocketTransport(s)
    raise SystemExit(f"Unsupported URI {uri!r}; use bt://, serial:// or tcp://")
