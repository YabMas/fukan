---
name: hugoduncan-babashkaprocess
description: Clojure library for spawning sub-processes and shell operations
---

# babashka.process

A Clojure library for shelling out and spawning sub-processes. Wraps `java.lang.ProcessBuilder` with an ergonomic API supporting pipelines, streaming I/O, and process control.

## Overview

babashka.process provides two main entry points:
- `shell` - High-level convenience function with sensible defaults
- `process` - Low-level function for fine-grained control

Included in Babashka since v0.2.3. Also usable as a JVM library.

**Repository:** https://github.com/babashka/process

## Installation

```clojure
;; deps.edn
{:deps {babashka/process {:mvn/version "0.6.25"}}}
```

Built into Babashka - no installation needed for bb scripts.

## Core Concepts

### shell vs process

| Aspect | `shell` | `process` |
|--------|---------|-----------|
| Blocking | Yes | No (returns immediately) |
| Exit check | Throws on non-zero | No checking |
| I/O default | `:inherit` (console) | Streams |
| Tokenization | Auto-tokenizes first arg | Manual |

Use `shell` for simple commands. Use `process` for pipelines, streaming, or async operations.

### Process Records

Both functions return a record containing:
- `:proc` - `java.lang.Process` instance
- `:in` - Input stream (stdin)
- `:out` - Output stream (stdout)
- `:err` - Error stream (stderr)
- `:cmd` - Command vector
- `:prev` - Previous process (pipelines)

Dereferencing (`@` or `deref`) waits for completion and adds `:exit`.

## API Reference

### shell

High-level function for running external programs.

```clojure
(require '[babashka.process :refer [shell]])

;; Basic usage - tokenizes automatically
(shell "ls -la")

;; Multiple arguments
(shell "git" "commit" "-m" "message")

;; With options
(shell {:dir "src"} "ls")

;; Capture output
(-> (shell {:out :string} "echo hello") :out)
;; => "hello\n"

;; Continue on error (don't throw)
(shell {:continue true} "ls nonexistent")
```

**Options:**
- `:continue` - Don't throw on non-zero exit
- All `process` options supported

### process

Low-level function with no opinionated defaults.

```clojure
(require '[babashka.process :refer [process]])

;; Returns immediately
(def p (process "sleep" "5"))

;; Deref to wait and get exit code
(:exit @p)

;; Capture output
(->> (process {:out :string} "ls") deref :out)
```

### check

Wait for process and throw on non-zero exit.

```clojure
(require '[babashka.process :refer [process check]])

;; Throws if ls fails
(->> (process {:out :string} "ls") check :out)

;; Chain with process
(-> (process "make") check)
```

### sh

Convenience wrapper defaulting `:out` and `:err` to `:string`.

```clojure
(require '[babashka.process :refer [sh]])

(sh "ls" "-la")
;; => {:exit 0 :out "..." :err ""}
```

### $

Macro for shell-like syntax with interpolation.

```clojure
(require '[babashka.process :refer [$]])

(def file "README.md")
($ ls -la ~file)

;; With options via metadata
(^{:out :string} $ echo hello)
```

### tokenize

Split string into argument vector.

```clojure
(require '[babashka.process :refer [tokenize]])

(tokenize "ls -la")
;; => ["ls" "-la"]

(tokenize "echo 'hello world'")
;; => ["echo" "hello world"]
```

### alive?

Check if process is running.

```clojure
(require '[babashka.process :refer [process alive?]])

(def p (process "sleep" "10"))
(alive? p) ;; => true
```

### destroy / destroy-tree

Terminate process. `destroy-tree` also kills descendants (JDK9+).

```clojure
(require '[babashka.process :refer [process destroy destroy-tree]])

(def p (process "sleep" "100"))
(destroy p)

;; Kill process and all children
(destroy-tree p)
```

### exec

Replace current process image (GraalVM/Babashka only).

```clojure
(require '[babashka.process :refer [exec]])

;; Replaces bb process with ls
(exec "ls" "-la")
```

### pb / pipeline

Create process builders for pipelines.

```clojure
(require '[babashka.process :refer [pb pipeline]])

;; JDK9+ pipeline
(-> (pipeline (pb "cat" "file.txt")
              (pb "grep" "pattern")
              (pb "wc" "-l"))
    last
    deref
    :out
    slurp)
```

## Options Reference

### I/O Options

| Option | Values | Description |
|--------|--------|-------------|
| `:in` | stream, string, `:inherit` | Stdin source |
| `:out` | `:string`, `:bytes`, `:inherit`, `:write`, `:append`, file | Stdout destination |
| `:err` | Same as `:out`, plus `:out` to merge | Stderr destination |
| `:in-enc` | charset | Input encoding |
| `:out-enc` | charset | Output encoding |
| `:err-enc` | charset | Error encoding |

### Process Options

| Option | Description |
|--------|-------------|
| `:dir` | Working directory |
| `:env` | Replace environment (map) |
| `:extra-env` | Add to environment (map) |
| `:inherit` | If true, inherit all streams |
| `:cmd` | Command vector (overrides args) |
| `:prev` | Previous process for piping |

### Hooks

| Option | Description |
|--------|-------------|
| `:pre-start-fn` | Called before start with process info |
| `:shutdown` | Called when child process ends |
| `:exit-fn` | Called on exit (JDK11+) |

## Common Patterns

### Capture Output

```clojure
;; As string
(-> (shell {:out :string} "date") :out str/trim)

;; As bytes
(-> (shell {:out :bytes} "cat" "image.png") :out)

;; Merge stderr into stdout
(shell {:err :out :out :string} "cmd")
```

### Working Directory

```clojure
(shell {:dir "/tmp"} "ls")
```

### Environment Variables

```clojure
;; Add to environment
(shell {:extra-env {"DEBUG" "1"}} "./script.sh")

;; Replace environment
(shell {:env {"PATH" "/usr/bin"}} "ls")
```

### Piping Processes

```clojure
;; Using threading
(->> (process "cat" "file.txt")
     (process {:out :string} "grep" "pattern")
     deref
     :out)

;; Using pipeline (JDK9+)
(-> (pipeline (pb "ls") (pb "grep" "clj"))
    last deref :out slurp)
```

### Input to Process

```clojure
;; String input
(-> (process {:in "hello\nworld" :out :string} "cat")
    deref :out)

;; File input
(-> (process {:in (io/file "data.txt") :out :string} "wc")
    deref :out)
```

### Close Stdin

For processes that read stdin until EOF, close it immediately:

```clojure
;; Empty string - simplest approach
(process {:in ""} "cmd")

;; Null device
(process {:in null-file} "cmd")

;; Explicit close after start
(let [p (process "cmd")]
  (.close (:in p))
  p)
```

### Streaming Output

```clojure
(require '[clojure.java.io :as io])

(def p (process {:err :inherit} "bb" "-e" "(doseq [i (range)] (println i) (Thread/sleep 100))"))

(with-open [rdr (io/reader (:out p))]
  (doseq [line (line-seq rdr)]
    (println "Got:" line)))
```

### Interactive Process

```clojure
(def p (process "cat"))
(def w (io/writer (:in p)))

(binding [*out* w]
  (println "hello")
  (println "world"))
(.close w)

(slurp (:out p))
;; => "hello\nworld\n"
```

### Write to File

```clojure
;; Overwrite
(shell {:out :write :out-file "log.txt"} "ls")

;; Append
(shell {:out :append :out-file "log.txt"} "date")
```

### Discard Output

```clojure
(require '[babashka.process :refer [shell null-file]])

(shell {:out null-file :err null-file} "noisy-command")
```

### Timeout

```clojure
(let [p (process "sleep" "100")]
  (when-not (deref p 1000 nil)
    (destroy-tree p)
    (println "Timed out")))
```

### Pre-start Hook

```clojure
(shell {:pre-start-fn (fn [{:keys [cmd]}]
                        (println "Running:" cmd))}
       "ls")
```

## Error Handling

### Check Exit Code

```clojure
;; shell throws by default
(try
  (shell "ls" "nonexistent")
  (catch Exception e
    (println "Failed:" (ex-message e))))

;; Suppress with :continue
(let [{:keys [exit]} (shell {:continue true} "ls" "nonexistent")]
  (when-not (zero? exit)
    (println "Command failed")))
```

### Process Errors

```clojure
;; Manual checking with process
(let [{:keys [exit out err]} @(process {:out :string :err :string} "cmd")]
  (if (zero? exit)
    (println "Success:" out)
    (println "Error:" err)))
```

### Capture Stderr

```clojure
(let [{:keys [err]} (shell {:err :string :continue true} "ls" "nonexistent")]
  (println "Error output:" err))
```

## Performance Tips

1. **Avoid shell for simple operations** - Use Clojure/Java directly when possible
2. **Stream large outputs** - Don't use `:string` for large data; stream instead
3. **Reuse process builders** - For repeated commands, create `pb` once
4. **Use `destroy-tree`** - Prevent zombie processes when killing

## Platform Notes

### Windows

- Environment variable names are case-sensitive for `:extra-env`
- Cannot launch `.ps1` scripts directly; invoke through PowerShell:
  ```clojure
  (shell "powershell" "-File" "script.ps1")
  ```
- Globbing doesn't work; expand patterns in Clojure

### macOS/Linux

- First argument not tokenized if it contains spaces without quotes
- Use `tokenize` explicitly when needed

## Comparison with clojure.java.shell

| Feature | `clojure.java.shell/sh` | `babashka.process` |
|---------|------------------------|-------------------|
| Blocking | Always | Explicit via deref |
| Piping | No | Yes |
| Streaming | No | Yes |
| Process control | Limited | Full access |
| Exit checking | Manual | `check` / `shell` |

## See Also

- [babashka.fs](../babashka.fs/) - File system operations
- [babashka-cli](../babashka-cli/) - CLI argument parsing
