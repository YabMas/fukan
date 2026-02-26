(ns tasks.agent-session
  "jj workspace lifecycle for parallel agent sessions.

   Each session creates a jj workspace at /tmp/<project>-<name>/
   backed by the same repo. Agents work in their workspace directory
   and commits appear immediately in the shared history."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def ^:private project-name
  (-> (System/getProperty "user.dir")
      (str/split #"/")
      last))

(defn- workspace-path [name]
  (str "/tmp/" project-name "-" name))

(defn- workspace-name [name]
  (str project-name "-" name))

(defn- jj [& args]
  (let [result (apply p/sh "jj" args)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "jj failed: " (str/trim (:err result)))
                      {:cmd (cons "jj" args) :exit (:exit result)})))
    (str/trim (:out result))))

(defn- workspace-exists? [name]
  (let [output (jj "workspace" "list")]
    (some #(str/starts-with? % (str (workspace-name name) ":"))
          (str/split-lines output))))

(defn start
  "Create a jj workspace for an agent session.

   Prints the workspace path — cd there and run `claude` to begin."
  [name]
  (when (str/blank? name)
    (throw (ex-info "Usage: bb agent:session:start <name>" {})))
  (let [ws-name (workspace-name name)
        ws-path (workspace-path name)]
    (when (workspace-exists? name)
      (throw (ex-info (str "Session '" name "' already exists. Stop it first or choose another name.")
                      {:workspace ws-name})))
    (jj "workspace" "add" ws-path)
    (println "Session started:" name)
    (println "  workspace:" ws-path)
    (println)
    (println "  cd" ws-path "&&" "claude")))

(defn stop
  "Tear down a jj workspace session.

   Abandons the workspace's empty commit, forgets the workspace,
   and removes the temp directory. Non-empty commits are kept."
  [name]
  (when (str/blank? name)
    (throw (ex-info "Usage: bb agent:session:stop <name>" {})))
  (let [ws-name (workspace-name name)
        ws-path (workspace-path name)]
    (when-not (workspace-exists? name)
      (throw (ex-info (str "No session named '" name "' found.")
                      {:workspace ws-name})))
    ;; Find the workspace's @ commit and abandon if empty
    (let [log-output (jj "log" "-r" (str ws-name "@") "--no-graph" "-T" "concat(change_id, \" \", empty)")
          [change-id empty?] (str/split (str/trim log-output) #" ")]
      (when (= empty? "true")
        (jj "abandon" change-id)))
    (jj "workspace" "forget" ws-name)
    (p/shell {:continue true} "rm" "-rf" ws-path)
    (println "Session stopped:" name)
    (println)
    ;; Show current state
    (println (jj "log" "--limit" "5"))))

(defn list-sessions
  "List active agent sessions."
  []
  (let [output (jj "workspace" "list")
        prefix (str project-name "-")
        sessions (->> (str/split-lines output)
                      (filter #(str/starts-with? % prefix)))]
    (if (seq sessions)
      (do
        (println "Active sessions:")
        (doseq [line sessions]
          (let [name (-> line (str/split #":") first (subs (count prefix)))]
            (println " " name "→" (workspace-path name)))))
      (println "No active sessions."))))
