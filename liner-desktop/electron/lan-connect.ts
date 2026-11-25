import dgram from "node:dgram";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import crypto from "node:crypto";
import { app, BrowserWindow } from "electron";
import { WebSocketServer, WebSocket, type RawData } from "ws";
import { isAppUrl } from "./ipc-guard.js";
import type { StoredAuthSession } from "./auth-session-store.js";

export const LAN_DISCOVERY_PORT = 45788;
export const LAN_WS_PORT = 45789;
export const LAN_DISCOVER_PROBE = "LINER_DISCOVER";
export const LAN_WS_CLOSE_USER_MISMATCH = 4001;

export interface LanTrackPayload {
  id: string;
  title: string;
  artist: string;
  coverUrl: string;
  durationMs: number;
}

export interface LanPlaybackStatePayload {
  isPlaying: boolean;
  positionMs: number;
  durationMs: number;
  volume?: number;
  isSlave?: boolean;
  updatedAt: number;
  track: LanTrackPayload | null;
}

export interface LanRemoteState extends LanPlaybackStatePayload {
  type: "state";
  deviceId: string;
  deviceName: string;
  platform: string;
}

export type LanCommandAction =
  | "play"
  | "pause"
  | "toggle"
  | "next"
  | "prev"
  | "seek"
  | "handoff"
  | "setVolume";

const VALID_COMMAND_ACTIONS = new Set<LanCommandAction>([
  "play",
  "pause",
  "toggle",
  "next",
  "prev",
  "seek",
  "handoff",
  "setVolume",
]);

export interface LanCommandPayload {
  action: LanCommandAction;
  positionMs?: number;
  volume?: number;
  track?: LanTrackPayload | null;
  queue?: LanTrackPayload[];
  startIndex?: number;
  shuffle?: boolean;
  targetDeviceId?: string;
}

export interface LanRemoteCommand extends LanCommandPayload {
  type: "command";
  fromDeviceId?: string;
  fromDeviceName?: string;
}

export interface LanPeerInfo {
  deviceId: string;
  deviceName: string;
  platform: string;
  connectedAt: number;
}

interface AuthenticatedPeer {
  socket: WebSocket;
  deviceId: string;
  deviceName: string;
  platform: string;
  connectedAt: number;
}

function loadOrCreateDeviceId(userDataDir: string): string {
  const filePath = path.join(userDataDir, "lan-connect-device.json");
  try {
    const raw = fs.readFileSync(filePath, "utf8");
    const parsed = JSON.parse(raw) as { deviceId?: unknown };
    if (typeof parsed.deviceId === "string" && parsed.deviceId.length > 0) {
      return parsed.deviceId;
    }
  } catch {
    // ignore missing/corrupt file and generate a new id
  }
  const deviceId = crypto.randomUUID();
  try {
    fs.mkdirSync(userDataDir, { recursive: true });
    fs.writeFileSync(filePath, JSON.stringify({ deviceId }), "utf8");
  } catch (err) {
    console.warn("[LanConnect] failed to persist deviceId:", err);
  }
  return deviceId;
}

export function extractUserIdFromSession(
  session: StoredAuthSession | null,
): string | null {
  if (!session) return null;
  const userObj = session.user as Record<string, unknown> | undefined;
  if (userObj && typeof userObj.id === "string" && userObj.id.length > 0) {
    return userObj.id;
  }
  if (
    typeof session.accessToken === "string" &&
    session.accessToken.length > 0
  ) {
    try {
      const parts = session.accessToken.split(".");
      if (parts.length >= 2) {
        const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
        const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
        const payload = JSON.parse(
          Buffer.from(padded, "base64").toString("utf8"),
        ) as Record<string, unknown>;
        const sub = payload.sub ?? payload.userId ?? payload.id;
        if (typeof sub === "string" && sub.length > 0) {
          return sub;
        }
      }
    } catch {
      // ignore invalid jwt
    }
  }
  return null;
}

function rawDataToString(data: RawData): string {
  if (typeof data === "string") return data;
  if (Array.isArray(data)) return Buffer.concat(data).toString("utf8");
  if (data instanceof ArrayBuffer) return Buffer.from(data).toString("utf8");
  return data.toString("utf8");
}

export class LanConnectManager {
  public readonly deviceId: string;
  public readonly deviceName: string;

  private userId: string | null = null;
  private udpSocket: dgram.Socket | null = null;
  private wss: WebSocketServer | null = null;
  private peers = new Map<WebSocket, AuthenticatedPeer>();
  private remoteStates = new Map<string, LanRemoteState>();
  private lastDesktopState: LanPlaybackStatePayload | null = null;
  private destroyed = false;

  constructor() {
    this.deviceId = loadOrCreateDeviceId(app.getPath("userData"));
    this.deviceName = os.hostname() || "Liner Desktop";
    this.startUdpDiscovery();
    this.startWebSocketServer();
  }

  public setUserId(nextUserId: string | null): void {
    const normalized =
      typeof nextUserId === "string" && nextUserId.length > 0
        ? nextUserId
        : null;
    if (this.userId === normalized) return;
    const prevUserId = this.userId;
    this.userId = normalized;

    if (prevUserId) {
      const clearedDeviceIds = new Set<string>([
        ...Array.from(this.remoteStates.keys()),
        ...Array.from(this.peers.values()).map((p) => p.deviceId),
      ]);
      for (const [ws] of this.peers) {
        try {
          ws.close(
            LAN_WS_CLOSE_USER_MISMATCH,
            normalized ? "Account switched" : "Signed out",
          );
        } catch {}
      }
      this.peers.clear();
      this.remoteStates.clear();
      this.broadcastPeersToRenderer();
      for (const deviceId of clearedDeviceIds) {
        this.sendToRenderer("lan-connect:remote-state-cleared", { deviceId });
      }
    }

    if (normalized) {
      this.broadcastBeacon();
    }
  }

  public sendDesktopState(state: LanPlaybackStatePayload): void {
    this.lastDesktopState = state;
    if (!this.userId || this.peers.size === 0) return;
    const msg = JSON.stringify({
      type: "state",
      deviceId: this.deviceId,
      deviceName: this.deviceName,
      platform: "desktop",
      ...state,
    });
    for (const [ws] of this.peers) {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(msg);
      }
    }
  }

  public sendCommand(command: LanCommandPayload): void {
    if (!this.userId || this.peers.size === 0) return;
    if (
      !command ||
      typeof command !== "object" ||
      !VALID_COMMAND_ACTIONS.has(command.action)
    ) {
      return;
    }
    const targetDeviceId =
      typeof command.targetDeviceId === "string" &&
      command.targetDeviceId.length > 0
        ? command.targetDeviceId
        : undefined;
    const sanitized: Record<string, unknown> = {
      type: "command",
      fromDeviceId: this.deviceId,
      fromDeviceName: this.deviceName,
      action: command.action,
    };
    if (
      typeof command.positionMs === "number" &&
      Number.isFinite(command.positionMs)
    ) {
      sanitized.positionMs = command.positionMs;
    }
    if (typeof command.volume === "number" && Number.isFinite(command.volume)) {
      sanitized.volume = Math.max(0, Math.min(1, command.volume));
    }
    if (command.track !== undefined) {
      if (command.track === null) {
        sanitized.track = null;
      } else if (typeof command.track === "object") {
        sanitized.track = command.track;
      }
    }
    if (Array.isArray(command.queue)) {
      sanitized.queue = command.queue.filter(
        (item): item is LanTrackPayload =>
          Boolean(item) && typeof item === "object",
      );
    }
    if (
      typeof command.startIndex === "number" &&
      Number.isFinite(command.startIndex)
    ) {
      sanitized.startIndex = Math.trunc(command.startIndex);
    }
    if (typeof command.shuffle === "boolean") {
      sanitized.shuffle = command.shuffle;
    }
    const msg = JSON.stringify(sanitized);
    for (const [ws, peer] of this.peers) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      if (targetDeviceId && peer.deviceId !== targetDeviceId) continue;
      ws.send(msg);
    }
  }

  public getStatus(): {
    deviceId: string;
    deviceName: string;
    userId: string | null;
    peers: LanPeerInfo[];
    remoteStates: LanRemoteState[];
  } {
    return {
      deviceId: this.deviceId,
      deviceName: this.deviceName,
      userId: this.userId,
      peers: this.listPeers(),
      remoteStates: Array.from(this.remoteStates.values()),
    };
  }

  public destroy(): void {
    if (this.destroyed) return;
    this.destroyed = true;
    for (const [ws] of this.peers) {
      try {
        ws.close(1000, "Server shutting down");
      } catch {}
    }
    this.peers.clear();
    this.remoteStates.clear();
    if (this.wss) {
      try {
        this.wss.close();
      } catch {}
      this.wss = null;
    }
    if (this.udpSocket) {
      try {
        this.udpSocket.close();
      } catch {}
      this.udpSocket = null;
    }
  }

  private listPeers(): LanPeerInfo[] {
    return Array.from(this.peers.values()).map((p) => ({
      deviceId: p.deviceId,
      deviceName: p.deviceName,
      platform: p.platform,
      connectedAt: p.connectedAt,
    }));
  }

  private buildBeaconJson(): string | null {
    if (!this.userId) return null;
    return JSON.stringify({
      type: "liner-connect-beacon",
      deviceId: this.deviceId,
      deviceName: this.deviceName,
      userId: this.userId,
      wsPort: LAN_WS_PORT,
    });
  }

  private broadcastBeacon(): void {
    const payload = this.buildBeaconJson();
    if (!payload || !this.udpSocket) return;
    const buf = Buffer.from(payload, "utf8");
    try {
      this.udpSocket.send(
        buf,
        0,
        buf.length,
        LAN_DISCOVERY_PORT,
        "255.255.255.255",
      );
    } catch {
      // ignore broadcast errors on interfaces without broadcast support
    }
  }

  private startUdpDiscovery(): void {
    const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
    this.udpSocket = socket;

    socket.on("error", (err) => {
      console.warn("[LanConnect] UDP discovery error:", err.message);
    });

    socket.on("message", (msg, rinfo) => {
      if (this.destroyed || !this.userId) return;
      const text = msg.toString("utf8").trim();
      if (text !== LAN_DISCOVER_PROBE) return;
      const beacon = this.buildBeaconJson();
      if (!beacon) return;
      const buf = Buffer.from(beacon, "utf8");
      socket.send(buf, 0, buf.length, rinfo.port, rinfo.address, (err) => {
        if (err) {
          console.warn("[LanConnect] UDP reply error:", err.message);
        }
      });
    });

    socket.bind(LAN_DISCOVERY_PORT, "0.0.0.0", () => {
      try {
        socket.setBroadcast(true);
      } catch {}
    });
  }

  private startWebSocketServer(): void {
    const wss = new WebSocketServer({
      host: "0.0.0.0",
      port: LAN_WS_PORT,
    });
    this.wss = wss;

    wss.on("error", (err) => {
      console.warn("[LanConnect] WebSocket server error:", err.message);
    });

    wss.on("connection", (ws) => {
      if (this.destroyed) {
        ws.close(1001, "Shutting down");
        return;
      }

      const handshakeTimer = setTimeout(() => {
        if (!this.peers.has(ws)) {
          try {
            ws.close(4000, "Hello timeout");
          } catch {}
        }
      }, 5000);

      ws.on("message", (raw) => {
        let parsed: Record<string, unknown>;
        try {
          parsed = JSON.parse(rawDataToString(raw)) as Record<string, unknown>;
        } catch {
          return;
        }

        const msgType = parsed.type;
        if (msgType === "hello") {
          clearTimeout(handshakeTimer);
          const peerUserId =
            typeof parsed.userId === "string" ? parsed.userId : null;
          if (!this.userId || !peerUserId || peerUserId !== this.userId) {
            ws.close(LAN_WS_CLOSE_USER_MISMATCH, "userId mismatch");
            return;
          }
          const deviceId =
            typeof parsed.deviceId === "string" && parsed.deviceId.length > 0
              ? parsed.deviceId
              : crypto.randomUUID();
          const deviceName =
            typeof parsed.deviceName === "string" &&
            parsed.deviceName.length > 0
              ? parsed.deviceName
              : "Android Device";
          const platform =
            typeof parsed.platform === "string" && parsed.platform.length > 0
              ? parsed.platform
              : "android";

          // Clean up previous peer registration if this socket re-authenticates with a different deviceId
          const prevPeer = this.peers.get(ws);
          if (prevPeer && prevPeer.deviceId !== deviceId) {
            this.remoteStates.delete(prevPeer.deviceId);
            this.sendToRenderer("lan-connect:remote-state-cleared", {
              deviceId: prevPeer.deviceId,
            });
          }

          // Close any stale socket for the same deviceId
          for (const [existingWs, existingPeer] of this.peers) {
            if (existingWs !== ws && existingPeer.deviceId === deviceId) {
              this.peers.delete(existingWs);
              try {
                existingWs.close(1000, "Replaced by new connection");
              } catch {}
            }
          }

          const peer: AuthenticatedPeer = {
            socket: ws,
            deviceId,
            deviceName,
            platform,
            connectedAt: Date.now(),
          };
          this.peers.set(ws, peer);

          ws.send(
            JSON.stringify({
              type: "hello",
              deviceId: this.deviceId,
              deviceName: this.deviceName,
              platform: "desktop",
              userId: this.userId,
            }),
          );

          if (this.lastDesktopState) {
            ws.send(
              JSON.stringify({
                type: "state",
                deviceId: this.deviceId,
                deviceName: this.deviceName,
                platform: "desktop",
                ...this.lastDesktopState,
              }),
            );
          }

          this.broadcastPeersToRenderer();
          return;
        }

        const peer = this.peers.get(ws);
        if (!peer) {
          ws.close(LAN_WS_CLOSE_USER_MISMATCH, "Not authenticated");
          return;
        }

        if (msgType === "state") {
          const remoteState: LanRemoteState = {
            type: "state",
            deviceId: peer.deviceId,
            deviceName:
              typeof parsed.deviceName === "string"
                ? parsed.deviceName
                : peer.deviceName,
            platform:
              typeof parsed.platform === "string"
                ? parsed.platform
                : peer.platform,
            isPlaying: Boolean(parsed.isPlaying),
            isSlave:
              typeof parsed.isSlave === "boolean" ? parsed.isSlave : undefined,
            positionMs:
              typeof parsed.positionMs === "number" ? parsed.positionMs : 0,
            durationMs:
              typeof parsed.durationMs === "number" ? parsed.durationMs : 0,
            volume:
              typeof parsed.volume === "number" &&
              Number.isFinite(parsed.volume)
                ? Math.max(0, Math.min(1, parsed.volume))
                : undefined,
            updatedAt:
              typeof parsed.updatedAt === "number"
                ? parsed.updatedAt
                : Date.now(),
            track:
              parsed.track && typeof parsed.track === "object"
                ? (parsed.track as LanTrackPayload)
                : null,
          };
          this.remoteStates.set(peer.deviceId, remoteState);
          this.sendToRenderer("lan-connect:remote-state", remoteState);
          return;
        }

        if (msgType === "command") {
          const action = parsed.action;
          if (typeof action !== "string") return;
          const cmd: LanRemoteCommand = {
            type: "command",
            action: action as LanCommandAction,
            positionMs:
              typeof parsed.positionMs === "number"
                ? parsed.positionMs
                : undefined,
            volume:
              typeof parsed.volume === "number" &&
              Number.isFinite(parsed.volume)
                ? Math.max(0, Math.min(1, parsed.volume))
                : undefined,
            track:
              parsed.track && typeof parsed.track === "object"
                ? (parsed.track as LanTrackPayload)
                : undefined,
            queue: Array.isArray(parsed.queue)
              ? (parsed.queue.filter(
                  (item): item is LanTrackPayload =>
                    Boolean(item) && typeof item === "object",
                ) as LanTrackPayload[])
              : undefined,
            startIndex:
              typeof parsed.startIndex === "number" &&
              Number.isFinite(parsed.startIndex)
                ? parsed.startIndex
                : undefined,
            shuffle:
              typeof parsed.shuffle === "boolean" ? parsed.shuffle : undefined,
            fromDeviceId: peer.deviceId,
            fromDeviceName: peer.deviceName,
          };
          this.sendToRenderer("lan-connect:command", cmd);
        }
      });

      ws.on("close", () => {
        clearTimeout(handshakeTimer);
        const peer = this.peers.get(ws);
        if (!peer) return;
        this.peers.delete(ws);
        this.remoteStates.delete(peer.deviceId);
        this.broadcastPeersToRenderer();
        this.sendToRenderer("lan-connect:remote-state-cleared", {
          deviceId: peer.deviceId,
        });
      });

      ws.on("error", () => {
        // close event will clean up
      });
    });
  }

  private broadcastPeersToRenderer(): void {
    this.sendToRenderer("lan-connect:peers-changed", this.listPeers());
  }

  private sendToRenderer(channel: string, payload: unknown): void {
    for (const win of BrowserWindow.getAllWindows()) {
      if (win.isDestroyed()) continue;
      const contents = win.webContents;
      if (!isAppUrl(contents.getURL())) continue;
      contents.send(channel, payload);
    }
  }
}
