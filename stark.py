import os
import datetime

os.system("clear")

print("=" * 35)
print("       STARK AI v1.0")
print("    PERSONAL ASSISTANT")
print("=" * 35)
print("Hello! I am STARK.")
print("Your personal assistant is ready.")
print("Type 'help' to see my commands.")
print("Type 'exit' to close STARK.")
print()

while True:
    command = input("YOU: ").strip().lower()

    if command == "exit":
        print("STARK: Goodbye. See you again!")
        break

    elif command == "help":
        print("""
STARK COMMANDS:
1. hello
2. time
3. date
4. clear
5. help
6. exit
""")

    elif command == "hello":
        print("STARK: Hello! How can I help you?")

    elif command == "time":
        print("STARK:", datetime.datetime.now().strftime("%I:%M %p"))

    elif command == "date":
        print("STARK:", datetime.datetime.now().strftime("%d-%m-%Y"))

    elif command == "clear":
        os.system("clear")

    elif command == "":
        continue

    else:
        print("STARK: I don't know that command yet.")
