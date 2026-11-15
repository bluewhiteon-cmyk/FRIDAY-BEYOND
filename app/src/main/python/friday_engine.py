import os
import json
import datetime
import requests
import subprocess
from io import StringIO
from contextlib import redirect_stdout
from html.parser import HTMLParser
from urllib.parse import urlencode, urlparse, parse_qs

# FRIDAY BEYOND - Android and Termux personal assistant engine.
APP_DATA_DIR = os.environ.get("FRIDAY_DATA_DIR", os.getcwd())
os.makedirs(APP_DATA_DIR, exist_ok=True)

def data_path(filename):
    return os.path.join(APP_DATA_DIR, filename)

def secure_file_permissions(filename):
    try:
        path = data_path(filename)
        if os.path.isfile(path):
            os.chmod(path, 0o600)
            return True
    except OSError:
        pass
    return False

def secure_friday_files():
    names = [".env", "memory.json", "conversation_history.json"]
    return {name: secure_file_permissions(name) for name in names}

def read_env():
    values = {}
    try:
        with open(data_path(".env"), encoding="utf-8") as file:
            for line in file:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip()
    except OSError:
        pass
    return values

def load_key():
    return read_env().get("GEMINI_API_KEY", "")

def load_cloudflare():
    values = read_env()
    return (
        values.get("CLOUDFLARE_ACCOUNT_ID", ""),
        values.get("CLOUDFLARE_API_TOKEN", "")
    )

def normalize_fact(value):
    return " ".join(str(value).strip(" .,!?:;").casefold().split())

def load_memory():
    try:
        with open(data_path("memory.json"), encoding="utf-8") as file:
            data = json.load(file)
            return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}

def save_memory():
    with open(data_path("memory.json"), "w", encoding="utf-8") as file:
        json.dump(MEMORY, file, indent=4, ensure_ascii=False)

def load_history():
    try:
        with open(data_path("conversation_history.json"), encoding="utf-8") as file:
            data = json.load(file)
            return data[-10:] if isinstance(data, list) else []
    except (OSError, json.JSONDecodeError):
        return []

def save_history():
    with open(data_path("conversation_history.json"), "w", encoding="utf-8") as file:
        json.dump(history[-10:], file, indent=4, ensure_ascii=False)

def speak(message):
    try:
        subprocess.Popen(
            ["termux-tts-speak", "-r", "0.9", "-p", "0.8", str(message)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
    except Exception:
        print("FRIDAY: Voice output unavailable.")

def listen():
    print("FRIDAY: Listening... Speak now.")
    try:
        result = subprocess.run(
            ["termux-speech-to-text"],
            capture_output=True,
            text=True,
            timeout=30
        )
        spoken = result.stdout.strip()
        if spoken:
            print("YOU (voice):", spoken)
            return spoken
        print("FRIDAY: I didn't hear anything.")
    except subprocess.TimeoutExpired:
        print("FRIDAY: Listening timed out.")
    except Exception:
        print("FRIDAY: Microphone error.")
    return ""

API_KEY = load_key()
CLOUDFLARE_ACCOUNT_ID, CLOUDFLARE_API_TOKEN = load_cloudflare()
MEMORY = load_memory()
OWNER = MEMORY.get("owner_name", "Jack")
ASSISTANT = MEMORY.get("assistant_name", "FRIDAY")
URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
history = load_history()

def ask_ai(question, browser_mode=False):
    global history
    system_text = (
        f"You are {ASSISTANT}, personal AI of {OWNER}. Always call the owner {OWNER}. "
        "Answer the actual question directly and accurately. Explain simply and clearly. "
        "Use recent conversation to understand follow-ups and pronouns when clear. "
        "Do not ask for information already present in recent conversation. "
        "Never pretend to remember facts not in permanent memory. If unsure, say so. "
        "For science and space, distinguish established facts from theories. "
        "Never invent facts or statistics. Correct false assumptions. "
        "Permanent memory: " + json.dumps(MEMORY, ensure_ascii=False)
    )

    history.append({"role": "user", "parts": [{"text": str(question)}]})
    history = history[-10:]
    save_history()
    answer = ""

    # Gemini first, when an API key is configured.
    if API_KEY:
        try:
            payload = {
                "systemInstruction": {"parts": [{"text": system_text}]},
                "contents": history,
                "generationConfig": {"temperature": 0.7, "maxOutputTokens": 1000}
            }
            response = requests.post(
                URL,
                headers={
                    "x-goog-api-key": API_KEY,
                    "Content-Type": "application/json"
                },
                json=payload,
                timeout=45
            )
            if response.status_code == 200:
                data = response.json()
                answer = data["candidates"][0]["content"]["parts"][0]["text"].strip()
            else:
                print("FRIDAY: Gemini unavailable. Trying Cloudflare...")
        except (
            requests.exceptions.RequestException,
            KeyError, IndexError, ValueError, TypeError
        ):
            print("FRIDAY: Gemini failed. Trying Cloudflare...")
    else:
        print("FRIDAY: Gemini API key is not configured.")

    # Cloudflare fallback.
    if not answer and CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_API_TOKEN:
        try:
            cloudflare_url = (
                "https://api.cloudflare.com/client/v4/accounts/"
                + CLOUDFLARE_ACCOUNT_ID
                + "/ai/run/@cf/google/gemma-4-26b-a4b-it"
            )
            messages = [{"role": "system", "content": system_text}]
            for item in history:
                role = "assistant" if item.get("role") == "model" else "user"
                content = " ".join(
                    part.get("text", "") for part in item.get("parts", [])
                )
                messages.append({"role": role, "content": content})

            cf_response = requests.post(
                cloudflare_url,
                headers={
                    "Authorization": "Bearer " + CLOUDFLARE_API_TOKEN,
                    "Content-Type": "application/json"
                },
                json={
                    "messages": messages,
                    "max_tokens": 1000,
                    "temperature": 0.5,
                    "chat_template_kwargs": {"enable_thinking": False}
                },
                timeout=60
            )
            if cf_response.status_code == 200:
                cf_data = cf_response.json()
                result = cf_data.get("result", {})
                answer = str(result.get("response", "")).strip()
                if not answer:
                    choices = result.get("choices", [])
                    if choices:
                        content = choices[0].get("message", {}).get("content", "")
                        if isinstance(content, str):
                            answer = content.strip()
                if not answer:
                    print("FRIDAY: Cloudflare returned no answer.")
            else:
                print("FRIDAY: Cloudflare HTTP", cf_response.status_code)
                print("FRIDAY: Cloudflare error:", cf_response.text[:1200])
        except (requests.exceptions.RequestException, ValueError, TypeError):
            print("FRIDAY: Cloudflare connection problem.")
    elif not answer:
        print("FRIDAY: Cloudflare credentials are not configured.")

    if answer:
        history.append({"role": "model", "parts": [{"text": answer}]})
        history = history[-10:]
        save_history()
        if browser_mode:
            return answer
        print("\nFRIDAY:", answer, "\n")
        speak(answer)
        return answer

    if history and history[-1].get("role") == "user":
        history.pop()
        save_history()
    print("FRIDAY: Both AI services are unavailable. Local commands still work.")
    return False

def web_search(query, max_results=5):
    query = str(query).strip()
    if not query or len(query) > 300:
        return []
    try:
        max_results = max(1, min(int(max_results), 10))
    except (TypeError, ValueError):
        max_results = 5

    class SearchParser(HTMLParser):
        def __init__(self):
            super().__init__()
            self.results = []
            self.current = None
            self.capture = None

        def handle_starttag(self, tag, attrs):
            attrs = dict(attrs)
            classes = attrs.get("class", "").split()
            if tag == "a" and "result-link" in classes:
                if self.current:
                    self.results.append(self.current)
                self.current = {
                    "title": "", "url": attrs.get("href", ""), "snippet": ""
                }
                self.capture = "title"
            elif tag == "td" and "result-snippet" in classes and self.current:
                self.capture = "snippet"

        def handle_data(self, data):
            if self.current and self.capture:
                self.current[self.capture] += data

        def handle_endtag(self, tag):
            if tag == "a" and self.capture == "title":
                self.capture = None
            elif tag == "td" and self.capture == "snippet":
                self.results.append(self.current)
                self.current = None
                self.capture = None

    try:
        url = "https://lite.duckduckgo.com/lite/?" + urlencode({"q": query})
        response = requests.get(
            url, headers={"User-Agent": "Mozilla/5.0"}, timeout=10
        )
        response.raise_for_status()
        parser = SearchParser()
        parser.feed(response.text)
        if parser.current:
            parser.results.append(parser.current)

        results, seen_urls = [], set()
        for item in parser.results:
            raw_url = item["url"].strip()
            title = item["title"].strip()
            snippet = item["snippet"].strip()
            if raw_url.startswith("//"):
                raw_url = "https:" + raw_url
            parsed = urlparse(raw_url)
            if "duckduckgo.com" in parsed.netloc:
                destination = parse_qs(parsed.query).get("uddg", [])
                if destination:
                    raw_url = destination[0]
            parsed = urlparse(raw_url)
            if (
                title and parsed.scheme in ("http", "https")
                and parsed.netloc and raw_url not in seen_urls
            ):
                seen_urls.add(raw_url)
                results.append({
                    "title": title, "url": raw_url, "snippet": snippet
                })
            if len(results) >= max_results:
                break
        return results
    except Exception:
        return []

def memory_question_topic(question):
    question = " ".join(question.lower().strip(" ?.!\t").split())
    patterns = {
        "anime": ("anime", "manga"),
        "planet": ("planet", "planets"),
        "subject": ("subject", "favourite topic", "favorite topic"),
        "interests": (
            "interests", "hobbies", "things i enjoy",
            "hobby", "what i enjoy", "astronomy"
        ),
        "cars": ("car", "cars", "vehicle", "vehicles"),
        "name": ("my name", "owner name", "who am i"),
        "assistant": ("assistant", "your name", "who are you"),
        "device": ("phone", "device", "mobile"),
        "preferences": ("preferences", "how i like", "my preferences")
    }
    question_words = (
        "what ", "which ", "who ", "do you ", "tell me ", "can you ",
        "could you ", "you know ", "what is ", "what are ", "my ",
        "describe ", "tell ", "remind me ", "me about "
    )
    if not question.startswith(question_words):
        return None
    for topic, words in patterns.items():
        if any(word in question for word in words):
            return topic
    return None

def handle_command(command):
    lower = str(command).lower().strip()
    normalized = lower.rstrip("?.! ")

    if normalized in ("what time is it", "tell me the time", "current time"):
        lower = "time"
    elif normalized in (
        "what is the date", "what is todays date",
        "tell me the date", "what day is it"
    ):
        lower = "date"
    elif normalized in ("what is your name", "what are you called"):
        lower = "who are you"
    elif normalized in (
        "who is your owner", "who created you", "who do you belong to"
    ):
        lower = "who am i"
    elif normalized in ("how are you", "are you okay", "how are you doing"):
        lower = "how are you"

    if lower in ("exit", "quit", "close stark"):
        print("FRIDAY: Goodbye, Jack!")
        return False

    elif lower == "help":
        print("""
FRIDAY COMMANDS:
hello    - Greeting
time     - Current time
date     - Today's date
memory   - Show saved memory
remember - Save information
voice    - Start voice conversation
clear    - Clear screen
help     - Show commands
exit     - Close FRIDAY
""")

    elif lower in ("who are you", "your name"):
        message = f"I am {ASSISTANT}, your personal AI assistant."
        print("FRIDAY:", message)
        speak(message)

    elif lower in ("who am i", "my name"):
        message = f"You are {OWNER}, my owner."
        print("FRIDAY:", message)
        speak(message)

    elif lower == "about":
        message = (
            "I can greet you, tell the time and date, save memories, "
            "and communicate with online AI when the API is available."
        )
        print("FRIDAY:", message)
        speak(message)

    elif lower == "how are you":
        message = "I am functioning normally, Jack. All systems are online."
        print("FRIDAY:", message)
        speak(message)

    elif lower == "hello":
        message = f"Hello, {OWNER}! FRIDAY is online."
        print("FRIDAY:", message)
        speak(message)

    elif lower == "time":
        message = datetime.datetime.now().strftime("%I:%M %p")
        print("FRIDAY:", message)
        speak("The time is " + message)

    elif lower == "date":
        message = datetime.datetime.now().strftime("%d-%m-%Y")
        print("FRIDAY:", message)
        speak("Today's date is " + message)

    elif lower == "memory":
        print("FRIDAY: Here is what I remember:")
        print(json.dumps(MEMORY, indent=4, ensure_ascii=False))

    elif (
        normalized in (
            "what do you know about me", "what do you remember about me",
            "tell me about myself", "tell me about me"
        )
        or memory_question_topic(normalized) is not None
    ) and not (
        lower.startswith((
            "my favourite ", "my favorite ",
            "change my favourite ", "change my favorite "
        ))
        and (" is now " in lower or " to " in lower)
    ):
        facts = MEMORY.get("saved_facts", [])
        topic = memory_question_topic(normalized)
        if "about me" in lower or "myself" in lower:
            details = [
                f"Your name is {OWNER}.",
                f"Your phone is {MEMORY.get('device', 'not saved')}."
            ]
            details.extend(MEMORY.get("preferences", []))
            details.extend(facts)
            answer = "Here is what I remember about you: " + " ".join(details)
        elif topic == "name":
            answer = f"Your name is {OWNER}."
        elif topic == "assistant":
            answer = f"My name is {ASSISTANT}."
        elif topic == "device":
            answer = f"Your phone is {MEMORY.get('device', 'not saved')}."
        elif topic == "preferences":
            preferences = MEMORY.get("preferences", [])
            answer = (
                "Your saved preferences are: " + ". ".join(preferences)
                if preferences else "I don't have your preferences saved."
            )
        else:
            keywords = {
                "planet": ("planet",), "anime": ("anime",),
                "subject": ("subject",),
                "interests": ("enjoy", "interest", "hobby", "passion", "astronomy"),
                "cars": ("car", "vehicle")
            }
            matches = [
                fact for fact in facts
                if topic in keywords
                and any(word in fact.lower() for word in keywords[topic])
            ]
            if matches:
                formatted = [
                    fact[0].upper() + fact[1:] if fact else ""
                    for fact in matches
                ]
                answer = (
                    "Here are your interests: " + " ".join(formatted)
                    if topic == "interests" else formatted[0]
                )
            else:
                answer = "I don't have that information saved in permanent memory."
        print("FRIDAY:", answer)
        speak(answer)

    elif (
        lower.startswith(("my favourite ", "my favorite ",
                          "change my favourite ", "change my favorite "))
        and (" is now " in lower or " to " in lower)
    ):
        instruction = lower[7:] if lower.startswith("change ") else lower
        if " is now " in instruction:
            topic, value = instruction.split(" is now ", 1)
        else:
            topic, value = instruction.split(" to ", 1)
        topic = topic.strip(" .,!?:;")
        value = value.strip(" .,!?:;")
        facts = MEMORY.setdefault("saved_facts", [])
        matches = [fact for fact in facts if topic in fact.lower()]
        if len(matches) == 1 and value:
            index = facts.index(matches[0])
            label = "My " + topic[3:] if topic.startswith("my ") else topic
            facts[index] = label.capitalize() + " is " + value + "."
            save_memory()
            print("FRIDAY: Memory updated successfully, Jack!")
        elif not matches:
            print("FRIDAY: I couldn't find that fact in memory.")
        elif len(matches) > 1:
            print("FRIDAY: More than one fact matches. Be more specific.")
        else:
            print("FRIDAY: Tell me the new value.")

    elif lower.startswith("update ") and " to " in lower:
        instruction = command[7:].strip().rstrip(".")
        old_fact, new_fact = instruction.split(" to ", 1)
        old_fact = old_fact.strip(" .,!?:;")
        new_fact = new_fact.strip(" .,!?:;")
        facts = MEMORY.setdefault("saved_facts", [])
        matches = [
            fact for fact in facts
            if old_fact and old_fact.lower() in fact.lower()
        ]
        if len(matches) == 1 and new_fact:
            facts[facts.index(matches[0])] = new_fact
            save_memory()
            print("FRIDAY: Memory updated successfully, Jack!")
        elif not matches:
            print("FRIDAY: I couldn't find that fact.")
        elif len(matches) > 1:
            print("FRIDAY: More than one fact matches. Be more specific.")
        else:
            print("FRIDAY: Tell me the new fact.")

    elif lower.startswith("forget "):
        requested = lower[7:].strip(" .,!?:;")
        words = set(requested.split())
        facts = MEMORY.setdefault("saved_facts", [])
        matches = [
            fact for fact in facts
            if words and words.issubset(set(
                "".join(c.lower() if c.isalnum() else " " for c in fact).split()
            ))
        ]
        if len(matches) == 1:
            facts.remove(matches[0])
            save_memory()
            print("FRIDAY: Removed that fact from permanent memory, Jack!")
        elif len(matches) == 0:
            print("FRIDAY: I couldn't find that fact in permanent memory.")
        else:
            print("FRIDAY: More than one fact matches. Please be more specific.")

    elif (
        "remember this" in lower
        or lower.startswith((
            "remember that ", "don't forget that ", "dont forget that ",
            "save this ", "save that "
        ))
    ):
        markers = [
            "remember this", "remember that ", "don't forget that ",
            "dont forget that ", "save this ", "save that "
        ]
        marker = next((m for m in markers if lower.startswith(m)), None)
        if marker:
            fact = command[len(marker):].strip(" .,!?:;")
        else:
            marker_index = lower.rfind("remember this")
            fact = command[:marker_index].strip(" .,!?:;")
        if fact:
            facts = MEMORY.setdefault("saved_facts", [])
            duplicate = any(
                normalize_fact(existing) == normalize_fact(fact)
                for existing in facts
            )
            if duplicate:
                print("FRIDAY: That fact is already saved, Jack!")
            else:
                facts.append(fact)
                save_memory()
                print("FRIDAY: Saved to permanent memory, Jack!")
        else:
            print("FRIDAY: Tell me what you want me to remember.")

    elif lower == "remember":
        print("FRIDAY: In the app, use 'remember that ...' to save a fact.")
        if not ANDROID_MODE:
            fact = input("What should I remember? ").strip()
            if fact:
                facts = MEMORY.setdefault("saved_facts", [])
                if any(normalize_fact(x) == normalize_fact(fact) for x in facts):
                    print("FRIDAY: That fact is already saved, Jack!")
                else:
                    facts.append(fact)
                    save_memory()
                    print("FRIDAY: Saved to permanent memory, Jack!")
            else:
                print("FRIDAY: Nothing was saved.")

    elif lower.startswith("search "):
        query = command[7:].strip()
        if not query:
            print("FRIDAY: Tell me what you want to search.")
        else:
            print("FRIDAY: Searching the web...")
            results = web_search(query)
            if results:
                for index, item in enumerate(results, 1):
                    print(f"\n{index}. {item['title']}")
                    if item.get("snippet"):
                        print(item["snippet"])
                    print(item["url"])
            else:
                print("FRIDAY: No results found or the search service is unavailable.")

    elif lower == "clear":
        if not ANDROID_MODE:
            os.system("clear")
        else:
            print("FRIDAY: Chat is ready.")

    elif lower == "":
        pass

    elif lower.split(maxsplit=1)[0] in {
        "cd", "pwd", "ls", "python", "python3", "pip", "pkg", "apt",
        "grep", "sed", "cat", "mkdir", "cp", "mv", "rm", "nano",
        "vim", "chmod", "find", "du", "df", "top", "ps", "kill",
        "curl", "wget", "git", "bash", "sh", "source", "export", "env", "echo"
    }:
        print("FRIDAY: That looks like a Termux command.")
        print("FRIDAY: Run Termux commands in the Termux terminal.")

    elif not API_KEY and not (CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_API_TOKEN):
        print("FRIDAY: AI credentials are missing. Check your .env configuration.")

    else:
        result = ask_ai(command)
        if result is False:
            return None

    return True

def run_voice():
    print("\nFRIDAY: Voice mode started.")
    print("Say stop, bye, or exit to return to typing.")
    empty_count = 0
    while True:
        spoken = listen()
        if not spoken:
            empty_count += 1
            if empty_count >= 3:
                print("FRIDAY: No voice detected. Returning to typing.")
                return True
            continue
        empty_count = 0
        lower = spoken.lower().strip()
        words = set(lower.replace(".", " ").replace(",", " ").split())
        if (
            lower in ("stop", "bye", "goodbye", "exit", "quit")
            or any(phrase in lower for phrase in (
                "stop listening", "stop listen", "stop voice",
                "back to typing", "exit voice"
            ))
            or bool(words.intersection({"stop", "bye", "goodbye", "exit", "quit"}))
        ):
            print("FRIDAY: Returning to typing mode.")
            return True
        result = handle_command(spoken)
        if result is not True:
            print("FRIDAY: Returning to typing mode.")
            return True

def main():
    global ANDROID_MODE
    ANDROID_MODE = False
    security_results = secure_friday_files()
    os.system("clear")
    if all(security_results.values()):
        print("FRIDAY: Sensitive file protection active.")
    else:
        print("FRIDAY: WARNING - Check sensitive file permissions.")
    print("=" * 40)
    print("             FRIDAY AI")
    print("        PERSONAL ASSISTANT")
    print("=" * 40)
    print(f"Welcome back, {OWNER}!")
    print("FRIDAY: Systems online.")
    print("Type help for commands.")
    print("Type voice to start voice conversation.")
    print("Type exit to close FRIDAY.\n")
    while True:
        try:
            command = input("YOU (type or voice): ").strip()
            if command.lower() in ("voice", "listen"):
                if not run_voice():
                    break
            elif not handle_command(command):
                break
        except KeyboardInterrupt:
            print("\nFRIDAY: Goodbye, Jack!")
            break

# Android integration.
ANDROID_MODE = False

def configure_android(storage_path):
    global APP_DATA_DIR, MEMORY, OWNER, ASSISTANT, history
    global API_KEY, CLOUDFLARE_ACCOUNT_ID, CLOUDFLARE_API_TOKEN, ANDROID_MODE
    APP_DATA_DIR = storage_path
    os.makedirs(APP_DATA_DIR, exist_ok=True)
    ANDROID_MODE = True
    MEMORY = load_memory()
    OWNER = MEMORY.get("owner_name", "Jack")
    ASSISTANT = MEMORY.get("assistant_name", "FRIDAY")
    history = load_history()
    API_KEY = load_key()
    CLOUDFLARE_ACCOUNT_ID, CLOUDFLARE_API_TOKEN = load_cloudflare()
    return f"Welcome back, {OWNER}! FRIDAY is ready."

def android_chat(message):
    # IMPORTANT: global speak prevents the UnboundLocalError.
    global speak
    message = str(message).strip()
    if not message:
        return "I'm listening, Jack."
    if message.lower() == "remember":
        return "Tell me what to remember, Jack. Use: remember that ..."

    output = StringIO()
    original_speak = speak
    try:
        # Android chat displays text in the app; don't invoke Termux TTS.
        speak = lambda text: None
        with redirect_stdout(output):
            result = handle_command(message)
        if result is False:
            return "Goodbye, Jack!"
        response = output.getvalue().strip()
        return response or "I'm ready, Jack."
    except Exception as error:
        return "I encountered an error: " + str(error)
    finally:
        speak = original_speak

if __name__ == "__main__":
    main()
