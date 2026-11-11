// The browser side of the SeatWave live feed, for a reader to open by hand at
// http://127.0.0.1:8090/ while ./gradlew -q serve runs. It loads the seat map once, then follows
// the feed with EventSource or WebSocket from the map's position, and applies each change only if
// it is the next one; a reload message, or a change that is not the next one, loads the map again.
"use strict";

// The feed writes its WebSocket origin into the page, so the port is the one it really listens on.
const WEBSOCKET_ORIGIN = document.querySelector('meta[name="seatwave-websocket"]').content;
const SHOWN_SEATS = 1000;
const BACKOFF_BASE_MS = 500;
const BACKOFF_CAP_MS = 30000;

const mapElement = document.getElementById("map");
const stateElement = document.getElementById("state");
const methodElement = document.getElementById("method");
const seats = [];
let streamId = "";
let sequence = 0;
let connection = null;
let reconnectTimer = 0;
let failures = 0;
// Each restart starts a new generation; work left over from an older one (a map still loading, a
// reconnect timer, a socket's events) sees the number has moved on and stops.
let generation = 0;

for (let seatIndex = 0; seatIndex < SHOWN_SEATS; seatIndex++) {
  const seat = document.createElement("span");
  seat.title = "A-" + (seatIndex + 1);
  mapElement.appendChild(seat);
  seats.push(seat);
}

function show(seatIndex, code) {
  if (seatIndex < SHOWN_SEATS) {
    seats[seatIndex].className = code === "a" ? "" : code;
  }
}

// Returns the map's position, or null when a later restart has made this load stale.
async function loadMap(started) {
  const reply = await fetch("/v1/events/1/seat-map", { cache: "no-store" });
  const map = await reply.json();
  if (started !== generation) {
    return null;
  }
  [streamId, sequence] = splitPosition(map.position);
  for (let seatIndex = 0; seatIndex < SHOWN_SEATS; seatIndex++) {
    show(seatIndex, map.states[seatIndex]);
  }
  return map.position;
}

function splitPosition(position) {
  const dot = position.indexOf(".");
  return [position.slice(0, dot), Number(position.slice(dot + 1))];
}

// Returns false when the page must reload the map: a reload message, or a gap.
function apply(message) {
  if (message.type === "reload") {
    return false;
  }
  const [changeStream, changeSequence] = splitPosition(message.position);
  if (changeStream !== streamId || changeSequence > sequence + 1) {
    return false;
  }
  if (changeSequence <= sequence) {
    return true;
  }
  const [section, number] = message.seat.split("-");
  if (section === "A") {
    show(Number(number) - 1, message.state[0]);
  }
  sequence = changeSequence;
  return true;
}

async function follow(started) {
  const position = await loadMap(started);
  if (position === null) {
    return;
  }
  if (methodElement.value === "sse") {
    // EventSource reconnects by itself after the server's retry delay, to the same URL, and sends
    // the last id it saw in the Last-Event-ID header; the server resumes from there.
    const source = new EventSource("/v1/events/1/seat-changes/stream?after=" + position);
    connection = source;
    source.onopen = () => { stateElement.textContent = "open (server-sent events)"; };
    source.onerror = () => { stateElement.textContent = "reconnecting"; };
    source.addEventListener("seat", (event) => {
      if (started === generation && !apply(JSON.parse(event.data))) { restart(); }
    });
    source.addEventListener("reload", restart);
    return;
  }
  // A WebSocket does not reconnect by itself: the page backs off with full jitter and resumes from
  // the last change it applied.
  openSocket(started);
}

function openSocket(started) {
  const socket = new WebSocket(WEBSOCKET_ORIGIN
      + "/v1/events/1/seat-changes/socket?after=" + streamId + "." + sequence);
  connection = socket;
  socket.onopen = () => { failures = 0; stateElement.textContent = "open (WebSocket)"; };
  socket.onmessage = (event) => {
    if (started === generation && !apply(JSON.parse(event.data))) { restart(); }
  };
  socket.onclose = () => {
    if (started !== generation) {
      return;
    }
    stateElement.textContent = "reconnecting";
    const ceiling = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** failures++);
    reconnectTimer = setTimeout(() => openSocket(started), Math.random() * ceiling);
  };
}

// Ends everything the current generation owns, so exactly one connection follows the feed.
function restart() {
  generation++;
  clearTimeout(reconnectTimer);
  if (connection) {
    connection.onclose = null;
    connection.close();
    connection = null;
  }
  follow(generation);
}

methodElement.addEventListener("change", restart);
follow(generation);
