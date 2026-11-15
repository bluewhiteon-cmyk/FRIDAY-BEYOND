import os
import json
import datetime
import difflib
import requests
import subprocess


def secure_file_permissions(filename):
    """Restrict a sensitive file to its owner's permissions."""
    try:
        path = os.path.expanduser(filename)
        if os.path.isfile(path):
            os.chmod(path, 0o600)
            return True
    except OSError:
        pass
    return False



def secure_friday_files():
    """Apply owner-only permissions to existing sensitive files."""
    files = [".env", "memory.json", "conversation_history.json"]
    return {
        filename: secure_file_permissions(filename)
        for filename in files
    }


def load_key():
    try:
        with open(".env") as f:
            for line in f:
                if line.startswith("GEMINI_API_KEY="):
                    return line.strip().split("=", 1)[1]
    except FileNotFoundError:
        pass
    return ""

def load_cloudflare():
    values = {}
    try:
        with open(".env") as f:
            for line in f:
                if "=" in line and not line.startswith("#"):
                    key, value = line.strip().split("=", 1)
                    values[key] = value
    except FileNotFoundError:
        pass
    return values.get("CLOUDFLARE_ACCOUNT_ID", ""), values.get("CLOUDFLARE_API_TOKEN", "")

def normalize_fact(value):
    return " ".join(value.strip(" .,!?:;").casefold().split())


def load_memory():
    try:
        with open("memory.json") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}

def save_memory():
    with open("memory.json", "w") as f:
        json.dump(MEMORY, f, indent=4)

def load_history():
    try:
        with open("conversation_history.json") as f:
            data = json.load(f)
            return data[-10:] if isinstance(data, list) else []
    except (FileNotFoundError, json.JSONDecodeError):
        return []

def save_history():
    with open("conversation_history.json", "w") as f:
        json.dump(history, f, indent=4)

def speak(message):
    try:
        subprocess.Popen(
            ["termux-tts-speak", "-r", "0.9", "-p", "0.8", message],
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
    system_text = (
        f"You are {ASSISTANT}, personal AI of {OWNER}. "
        f"Always call the owner {OWNER}. "
        "Answer the user's actual question directly and accurately. "
        "Explain things simply, clearly, and in a useful amount of detail. "
        "Use the previous conversation to understand follow-up questions. "
        "Resolve words like he, she, it, they, this, that, and those using "
        "the recent conversation whenever the meaning is clear. "
        "Understand short follow-ups like why, how, tell me more, and what next "
        "using the previous topic. "
        "Do not ask the owner to repeat information already present in the "
        "recent conversation. If the reference is genuinely unclear, ask one "
        "short clarification question. "
        "Never pretend to remember facts that are not in permanent memory. "
        "If unsure, say so instead of inventing information. "
        "For science and space questions, explain the evidence and distinguish "
        "established facts from theories. Never invent facts or statistics. Admit uncertainty. Correct false assumptions. Prefer verified information. Permanent memory: "
        + json.dumps(MEMORY)
    )

    history.append({"role": "user", "parts": [{"text": question}]})
    save_history()
    answer = ""

    # First provider: Gemini
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

    except (requests.exceptions.RequestException, KeyError, IndexError, ValueError):
        print("FRIDAY: Gemini failed. Trying Cloudflare...")

    # Second provider: Cloudflare Workers AI
    if not answer and CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_API_TOKEN:
        try:
            cloudflare_url = (
                "https://api.cloudflare.com/client/v4/accounts/"
                + CLOUDFLARE_ACCOUNT_ID
                + "/ai/run/@cf/google/gemma-4-26b-a4b-it"
            )

            messages = [{"role": "system", "content": system_text}]
            for item in history:
                role = "assistant" if item["role"] == "model" else "user"
                content = " ".join(part.get("text", "") for part in item.get("parts", []))
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
                answer = result.get("response", "").strip()
                if not answer:
                    choices = result.get("choices", [])
                    if choices:
                        content = choices[0].get("message", {}).get("content", "")
                        if isinstance(content, str):
                            answer = content.strip()
                if not answer:
                    print("FRIDAY: Cloudflare response:", json.dumps(cf_data)[:1200])
            else:
                print("FRIDAY: Cloudflare HTTP", cf_response.status_code)
                print("FRIDAY: Cloudflare error:", cf_response.text[:1200])

            if not answer:
                print("FRIDAY: Cloudflare did not return an answer.")

        except (requests.exceptions.RequestException, ValueError):
            print("FRIDAY: Cloudflare connection problem.")

    elif not answer:
        print("FRIDAY: Cloudflare credentials are not configured.")

    if answer:
        history.append({"role": "model", "parts": [{"text": answer}]})
        if len(history) > 10:
            del history[:-10]
        save_history()
        if browser_mode:
            return answer

        print("\nFRIDAY:", answer, "\n")
        speak(answer)
        return answer

    if history and history[-1]["role"] == "user":
        history.pop()
        save_history()

    print("FRIDAY: Both AI services are unavailable. Local commands still work.")
    return False





def web_search(query, max_results=5):
    """Search DuckDuckGo Lite using Python's built-in HTML parser."""
    query = str(query).strip()

    if not query or len(query) > 300:
        return []

    try:
        max_results = max(1, min(int(max_results), 10))
    except (TypeError, ValueError):
        max_results = 5

    from html.parser import HTMLParser
    from urllib.parse import urlencode, urlparse, parse_qs

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
                    "title": "",
                    "url": attrs.get("href", ""),
                    "snippet": ""
                }
                self.capture = "title"

            elif tag == "td" and "result-snippet" in classes:
                if self.current:
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
            url,
            headers={"User-Agent": "Mozilla/5.0"},
            timeout=10
        )
        response.raise_for_status()

        parser = SearchParser()
        parser.feed(response.text)

        if parser.current:
            parser.results.append(parser.current)

        results = []
        seen_urls = set()

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
                title
                and parsed.scheme in ("http", "https")
                and parsed.netloc
                and raw_url not in seen_urls
            ):
                seen_urls.add(raw_url)
                results.append({
                    "title": title,
                    "url": raw_url,
                    "snippet": snippet
                })

            if len(results) >= max_results:
                break

        return results

    except requests.exceptions.RequestException:
        return []
    except Exception:
        return []

def memory_question_topic(question):
    question = question.lower().strip(" ?.!")
    question = " ".join(question.split())

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
        "what ", "which ", "who ", "do you ", "tell me ",
        "can you ", "could you ", "you know ",
        "what is ", "what are ", "my ", "describe ",
        "tell ", "tell me ", "remind me ", "me about "
    )

    if not question.startswith(question_words):
        return None

    for topic, words in patterns.items():
        if any(word in question for word in words):
            return topic

    return None


def handle_command(command):
    lower = command.lower().strip()
    normalized = lower.rstrip("?.! ")
    if normalized in ("what time is it", "tell me the time", "current time"):
        lower = "time"
    elif normalized in ("what is the date", "what is todays date", "tell me the date", "what day is it"):
        lower = "date"
    elif normalized in ("what is your name", "what are you called"):
        lower = "who are you"
    elif normalized in ("who is your owner", "who created you", "who do you belong to"):
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
        message = "I am FRIDAY, your personal AI assistant."
        print("FRIDAY:", message)
        speak(message)

    elif lower in ("who am i", "my name"):
        message = f"You are {OWNER}, my owner."
        print("FRIDAY:", message)
        speak(message)

    elif lower == "about":
        message = "I can greet you, tell the time and date, save memories, and communicate with online AI when the API is available."
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
        print(json.dumps(MEMORY, indent=4))

    elif (
        normalized in (
            "what do you know about me",
            "what do you remember about me",
            "tell me about myself",
            "tell me about me"
        )
        or memory_question_topic(normalized) is not None
    ) and not (
        lower.startswith(("my favourite ", "my favorite ",
                          "change my favourite ", "change my favorite "))
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
                if preferences else
                "I don't have your preferences saved."
            )

        else:
            keywords = {
                "planet": ("planet",),
                "anime": ("anime",),
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
                    if topic == "interests"
                    else formatted[0]
                )
            else:
                answer = "I don't have that information saved in permanent memory."

        print("FRIDAY:", answer)
        speak(answer)

    elif (
        lower.startswith("my favourite ")
        or lower.startswith("my favorite ")
        or lower.startswith("change my favourite ")
        or lower.startswith("change my favorite ")
    ) and (" is now " in lower or " to " in lower):

        instruction = lower
        if instruction.startswith("change "):
            instruction = instruction[7:]

        if " is now " in instruction:
            topic, value = instruction.split(" is now ", 1)
        else:
            topic, value = instruction.split(" to ", 1)

        topic = topic.strip(" .,!?:;")
        value = value.strip(" .,!?:;")
        facts = MEMORY.setdefault("saved_facts", [])

        matches = [
            fact for fact in facts
            if topic in fact.lower()
        ]

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
            if words and words.issubset(
                set("".join(c.lower() if c.isalnum() else " " for c in fact).split())
            )
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
        or lower.startswith("remember that ")
        or lower.startswith("don't forget that ")
        or lower.startswith("dont forget that ")
        or lower.startswith("save this ")
        or lower.startswith("save that ")
    ):
        markers = [
            "remember this", "remember that ",
            "don't forget that ", "dont forget that ",
            "save this ", "save that "
        ]
        marker = next((m for m in markers if lower.startswith(m)), None)

        if marker:
            fact = command[len(marker):].strip(" .,!?:;")
        else:
            marker = lower.rfind("remember this")
            fact = command[:marker].strip(" .,!?:;")

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
        fact = input("What should I remember? ").strip()
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
                    print(f"\\n{index}. {item['title']}")
                    if item.get("snippet"):
                        print(item["snippet"])
                    print(item["url"])
            else:
                print("FRIDAY: No results found or the search service is unavailable.")

    elif lower == "clear":
        os.system("clear")

    elif lower == "":
        pass

    elif lower.split(maxsplit=1)[0] in {
        "cd", "pwd", "ls", "python", "python3", "pip",
        "pkg", "apt", "grep", "sed", "cat", "mkdir",
        "cp", "mv", "rm", "nano", "vim", "chmod",
        "find", "du", "df", "top", "ps", "kill",
        "curl", "wget", "git", "bash", "sh", "source",
        "export", "env", "echo"
    }:
        print("FRIDAY: That looks like a Termux command.")
        print("FRIDAY: Type 'exit' to return to the Termux prompt, then run it there.")

    elif not API_KEY:
        print("FRIDAY: API key missing. Check your .env file.")

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

if __name__ == "__main__":
    main()
