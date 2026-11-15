import os
import json
from pathlib import Path
from http.server import HTTPServer, BaseHTTPRequestHandler

PROJECT_DIR = Path(__file__).resolve().parent
os.chdir(PROJECT_DIR)

import stark_online as friday

HOST = "127.0.0.1"
PORT = 8765
MAX_MESSAGE_LENGTH = 4000

HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#100b1d">
<title>FRIDAY BEYOND</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#100b1d;color:#eee8ff;font-family:Arial,sans-serif}
header{text-align:center;padding:22px 12px;border-bottom:1px solid #49336a}
.logo{font-size:27px;letter-spacing:5px;color:#d9b8ff}
.status{font-size:12px;color:#a98acb;margin-top:8px}
main{max-width:760px;margin:auto;padding:16px}
.panel{background:#191228;border:1px solid #49336a;border-radius:18px;padding:15px;min-height:65vh;display:flex;flex-direction:column}
.identity{text-align:center;padding:15px}
.orb{width:76px;height:76px;margin:auto;border-radius:50%;background:radial-gradient(circle,#e4c7ff,#8751c9 48%,#24133c 75%);box-shadow:0 0 30px #995bdc}
.identity h2{font-weight:400;letter-spacing:3px;color:#d9b8ff;margin-bottom:5px}
.identity p{font-size:12px;color:#a98acb}
#messages{flex:1;overflow-y:auto;padding:10px 2px}
.message{padding:12px 14px;border-radius:14px;margin:10px 0;max-width:88%;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.5}
.friday{background:#29203d;border:1px solid #503a70;margin-right:auto}
.user{background:#49306a;margin-left:auto}
#status{min-height:20px;color:#b99bd9;font-size:12px;padding:5px}
form{display:flex;gap:8px}
input{min-width:0;flex:1;background:#100b1d;color:white;border:1px solid #65458a;border-radius:12px;padding:14px;font-size:16px;outline:none}
button{background:#8953bd;color:white;border:0;border-radius:12px;padding:0 20px;font-size:15px}
button:disabled{opacity:.5}
footer{text-align:center;color:#806b9c;font-size:11px;padding:15px}
</style>
</head>
<body>
<header>
<div class="logo">FRIDAY BEYOND</div>
<div class="status">PERSONAL AI SYSTEM · LOCAL CONNECTION</div>
</header>
<main>
<section class="panel">
<div class="identity">
<div class="orb"></div>
<h2>FRIDAY</h2>
<p>Intelligence with loyalty, responsibility and compassion.</p>
</div>
<div id="messages" aria-live="polite">
<div class="message friday">Hello Jack. I'm ready. How can I help you?</div>
</div>
<div id="status">Systems ready.</div>
<form id="chat">
<input id="question" maxlength="4000" placeholder="Message FRIDAY..." autocomplete="off" required>
<button id="send" type="submit">Send</button>
</form>
</section>
<footer>FRIDAY BEYOND · PRIVATE LOCAL INTERFACE</footer>
</main>
<script>
const form=document.getElementById("chat");
const input=document.getElementById("question");
const messages=document.getElementById("messages");
const status=document.getElementById("status");
const send=document.getElementById("send");

function addMessage(text,kind){
 const item=document.createElement("div");
 item.className="message "+kind;
 item.textContent=text;
 messages.appendChild(item);
 messages.scrollTop=messages.scrollHeight;
}

form.addEventListener("submit",async function(event){
 event.preventDefault();
 const question=input.value.trim();
 if(!question)return;
 addMessage(question,"user");
 input.value="";
 send.disabled=true;
 status.textContent="FRIDAY is thinking...";
 try{
  const response=await fetch("/chat",{
   method:"POST",
   headers:{"Content-Type":"application/json"},
   body:JSON.stringify({message:question})
  });
  const data=await response.json();
  if(!response.ok)throw new Error(data.error||"Request failed");
  addMessage(data.answer||"I couldn't produce an answer.","friday");
  status.textContent="Ready.";
 }catch(error){
  addMessage("Connection problem: "+error.message,"friday");
  status.textContent="Connection error.";
 }finally{
  send.disabled=false;
  input.focus();
 }
});
</script>
</body>
</html>
"""


class FridayHandler(BaseHTTPRequestHandler):
    def send_json(self, status_code, data):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/":
            self.send_error(404)
            return

        body = HTML.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/chat":
            self.send_json(404, {"error": "Not found"})
            return

        if self.headers.get("Host") not in (
            "127.0.0.1:8765", "localhost:8765"
        ):
            self.send_json(403, {"error": "Invalid local host"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_json(400, {"error": "Invalid request"})
            return

        if length < 1 or length > 12000:
            self.send_json(413, {"error": "Request too large"})
            return

        try:
            data = json.loads(self.rfile.read(length).decode("utf-8"))
            question = data.get("message", "")
            if not isinstance(question, str):
                raise ValueError
            question = question.strip()
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            self.send_json(400, {"error": "Invalid message"})
            return

        if not question or len(question) > MAX_MESSAGE_LENGTH:
            self.send_json(400, {"error": "Message must be 1–4000 characters"})
            return

        try:
            answer = friday.ask_ai(question, browser_mode=True)
            if answer is False:
                self.send_json(503, {"error": "AI services are unavailable"})
                return
            self.send_json(200, {"answer": answer})
        except Exception:
            self.send_json(500, {"error": "FRIDAY encountered an internal error"})

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    print("FRIDAY BEYOND browser interface")
    print("Open: http://127.0.0.1:8765")
    print("Local-only server. Press CTRL+C to stop.")
    server = HTTPServer((HOST, PORT), FridayHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nFRIDAY interface stopped.")
    finally:
        server.server_close()
