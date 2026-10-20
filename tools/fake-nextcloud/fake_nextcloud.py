"""Minimal fake Nextcloud for NextTask end-to-end tests: Login Flow v2 + CalDAV with sync tokens and ETags.

Used by scripts/smoke-test.sh, and handy for trying the app by hand. Everything is kept in memory, so a restart
starts over (sign the app in again then: its sync tokens no longer match).

Run: python3 -I fake_nextcloud.py  (port 8099; on a device, `adb reverse tcp:8099 tcp:8099` and sign in to
http://localhost:8099). Approve the login: curl localhost:8099/login/v2/flow/abc. User demo, app password secret.
Starts with a "Tasks" list: Call mom; Groceries > Bread, Milk; Laundry; Taxes > Forms.
Admin: GET /admin/state  -> tasks per calendar (uid, summary, parent, sort order, status)
       GET /admin/seed/<n> -> adds n top-level "Filler NN" tasks; GET /admin/log -> PUTs and DELETEs
"""
import json
import re
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

BASE = "http://localhost:8099"
HOME = "/remote.php/dav/calendars/demo/"
lock = threading.Lock()
state = {"approved": False, "token": 1, "calendars": {}, "log": []}


def ics(uid, summary, parent=None, order=None):
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//fake//EN", "BEGIN:VTODO", f"UID:{uid}",
             "DTSTAMP:20261020T100000Z", f"SUMMARY:{summary}", "STATUS:NEEDS-ACTION"]
    if parent:
        lines.append(f"RELATED-TO;RELTYPE=PARENT:{parent}")
    if order is not None:
        lines.append(f"X-APPLE-SORT-ORDER:{order}")
    lines += ["END:VTODO", "END:VCALENDAR"]
    return "\r\n".join(lines) + "\r\n"


def add_calendar(name, display, components=("VTODO",)):
    state["calendars"][name] = {"display": display, "components": list(components), "items": {}, "deleted": {}}


def put_item(cal, res, data):
    state["token"] += 1
    cal["items"][res] = {"data": data, "etag": f'"{uuid.uuid4().hex[:12]}"', "token": state["token"]}
    cal["deleted"].pop(res, None)


add_calendar("tasks", "Tasks")
seed = [("groceries", "Groceries", None), ("milk", "Milk", "groceries"), ("bread", "Bread", "groceries"),
        ("laundry", "Laundry", None), ("call", "Call mom", None), ("taxes", "Taxes", None), ("forms", "Forms", "taxes")]
for uid, summary, parent in seed:
    put_item(state["calendars"]["tasks"], f"{uid}.ics", ics(uid, summary, parent))


def prop(data, name):
    m = re.search(rf"^{name}(;[^:\r\n]*)?:(.*)$", data.replace("\r\n ", ""), re.M)
    return m.group(2).strip() if m else None


def ms(body, token=None):
    t = f"<d:sync-token>{BASE}/sync/{token}</d:sync-token>" if token is not None else ""
    return ('<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" '
            f'xmlns:a="http://apple.com/ns/ical/">{body}{t}</d:multistatus>')


def ok(href, props):
    return f"<d:response><d:href>{href}</d:href><d:propstat><d:prop>{props}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, code, body="", ctype="application/xml; charset=utf-8", headers=None):
        raw = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n).decode() if n else ""

    def authed(self):
        if self.headers.get("Authorization") != "Basic ZGVtbzpzZWNyZXQ=":
            self.send(401)
            return False
        return True

    def calendar(self):
        m = re.match(rf"^{HOME}([^/]+)/(.*)$", self.path)
        return (m.group(1), m.group(2)) if m else (None, None)

    def do_GET(self):
        if self.path == "/login/v2/flow/abc":
            state["approved"] = True
            return self.send(200, "approved", "text/plain")
        if self.path == "/admin/state":
            with lock:
                out = {name: sorted(({"uid": prop(i["data"], "UID"), "summary": prop(i["data"], "SUMMARY"),
                                      "parent": prop(i["data"], "RELATED-TO"), "order": prop(i["data"], "X-APPLE-SORT-ORDER"),
                                      "status": prop(i["data"], "STATUS")}
                                     for i in cal["items"].values()), key=lambda t: t["uid"])
                       for name, cal in state["calendars"].items()}
            return self.send(200, json.dumps(out, indent=1), "application/json")
        m = re.match(r"^/admin/seed/(\d+)$", self.path)
        if m:
            with lock:
                for n in range(int(m.group(1))):
                    put_item(state["calendars"]["tasks"], f"filler{n:02}.ics", ics(f"filler{n:02}", f"Filler {n:02}"))
            return self.send(200, "seeded", "text/plain")
        if self.path == "/admin/log":
            return self.send(200, "\n".join(state["log"]), "text/plain")
        self.send(404)

    def do_POST(self):
        self.body()
        if self.path == "/index.php/login/v2":
            return self.send(200, json.dumps({"poll": {"token": "abc", "endpoint": f"{BASE}/login/v2/poll"},
                                              "login": f"{BASE}/login/v2/flow/abc"}), "application/json")
        if self.path == "/login/v2/poll":
            if not state["approved"]:
                return self.send(404)
            return self.send(200, json.dumps({"server": BASE, "loginName": "demo", "appPassword": "secret"}), "application/json")
        self.send(404)

    def do_PROPFIND(self):
        self.body()
        if not self.authed():
            return
        if self.path == "/remote.php/dav/":
            return self.send(207, ms(ok(self.path, "<d:current-user-principal><d:href>/remote.php/dav/principals/users/demo/</d:href></d:current-user-principal>")))
        if self.path == "/remote.php/dav/principals/users/demo/":
            return self.send(207, ms(ok(self.path, f"<c:calendar-home-set><d:href>{HOME}</d:href></c:calendar-home-set>")))
        if self.path == HOME:
            with lock:
                parts = [ok(HOME, "<d:resourcetype><d:collection/></d:resourcetype>")]
                for name, cal in state["calendars"].items():
                    comps = "".join(f'<c:comp name="{c}"/>' for c in cal["components"])
                    parts.append(ok(f"{HOME}{name}/",
                                    "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>"
                                    f"<d:displayname>{escape(cal['display'])}</d:displayname>"
                                    f"<d:sync-token>{BASE}/sync/{state['token']}</d:sync-token>"
                                    f"<c:supported-calendar-component-set>{comps}</c:supported-calendar-component-set>"))
            return self.send(207, ms("".join(parts)))
        self.send(404)

    def do_REPORT(self):
        body = self.body()
        if not self.authed():
            return
        name, _ = self.calendar()
        with lock:
            cal = state["calendars"].get(name)
            if cal is None:
                return self.send(404)
            if "sync-collection" in body:
                m = re.search(r"<d:sync-token>([^<]*)</d:sync-token>", body)
                since = int(m.group(1).rsplit("/", 1)[1]) if m and m.group(1) else 0
                parts = [ok(f"{HOME}{name}/{res}", f"<d:getetag>{escape(i['etag'])}</d:getetag>")
                         for res, i in cal["items"].items() if i["token"] > since]
                parts += [f"<d:response><d:href>{HOME}{name}/{res}</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>"
                          for res, t in cal["deleted"].items() if t > since and since]
                return self.send(207, ms("".join(parts), state["token"]))
            hrefs = re.findall(r"<d:href>([^<]*)</d:href>", body)
            parts = []
            for href in hrefs:
                i = cal["items"].get(href.rsplit("/", 1)[1])
                if i:
                    parts.append(ok(href, f"<d:getetag>{escape(i['etag'])}</d:getetag><c:calendar-data>{escape(i['data'])}</c:calendar-data>"))
            return self.send(207, ms("".join(parts)))

    def do_PUT(self):
        data = self.body()
        if not self.authed():
            return
        name, res = self.calendar()
        with lock:
            cal = state["calendars"].get(name)
            if cal is None or not res:
                return self.send(404)
            existing = cal["items"].get(res)
            if self.headers.get("If-None-Match") == "*" and existing:
                return self.send(412)
            match = self.headers.get("If-Match")
            if match and (not existing or existing["etag"] != match):
                return self.send(412)
            put_item(cal, res, data)
            state["log"].append(f"PUT {name}/{res}")
            return self.send(201 if not existing else 204, headers={"ETag": cal["items"][res]["etag"]})

    def do_DELETE(self):
        self.body()
        if self.path.startswith("/ocs/"):
            return self.send(200, "{}", "application/json")
        if not self.authed():
            return
        name, res = self.calendar()
        with lock:
            cal = state["calendars"].get(name)
            if cal is None:
                return self.send(404)
            if not res:
                del state["calendars"][name]
                return self.send(204)
            if res not in cal["items"]:
                return self.send(404)
            del cal["items"][res]
            state["token"] += 1
            cal["deleted"][res] = state["token"]
            state["log"].append(f"DELETE {name}/{res}")
            return self.send(204)

    def do_MKCALENDAR(self):
        body = self.body()
        if not self.authed():
            return
        name, _ = self.calendar()
        with lock:
            if name in state["calendars"]:
                return self.send(405)
            m = re.search(r"<d:displayname>([^<]*)</d:displayname>", body)
            add_calendar(name, m.group(1) if m else name)
            return self.send(201)


ThreadingHTTPServer(("127.0.0.1", 8099), Handler).serve_forever()
