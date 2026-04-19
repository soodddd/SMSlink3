#!/usr/bin/env python3
from __future__ import annotations

import argparse
import socket
import threading


def pump(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            dst.sendall(chunk)
    except OSError:
        pass
    finally:
        for sock in (src, dst):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass


def handle_client(client: socket.socket, target_host: str, target_port: int) -> None:
    try:
        upstream = socket.create_connection((target_host, target_port), timeout=5)
    except OSError:
        client.close()
        return

    threading.Thread(target=pump, args=(client, upstream), daemon=True).start()
    threading.Thread(target=pump, args=(upstream, client), daemon=True).start()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-host", default="0.0.0.0")
    parser.add_argument("--listen-port", type=int, default=1716)
    parser.add_argument("--target-host", default="127.0.0.1")
    parser.add_argument("--target-port", type=int, default=2716)
    args = parser.parse_args()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.listen_host, args.listen_port))
    server.listen(64)
    print(
        f"QA host proxy listening on {args.listen_host}:{args.listen_port} -> "
        f"{args.target_host}:{args.target_port}",
        flush=True,
    )

    try:
        while True:
            client, _ = server.accept()
            threading.Thread(
                target=handle_client,
                args=(client, args.target_host, args.target_port),
                daemon=True,
            ).start()
    finally:
        server.close()


if __name__ == "__main__":
    main()
