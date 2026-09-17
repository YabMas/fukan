(ns phase-1-report
  "Phase 1 status, as a PR comment.

   brian's region model states Phase 1 in one sentence: `Transport has no arrow to Persistence or
   External — both are reached through Domain, and that single absence is the whole of Phase 1`,
   over a Persistence that `owns the connection; nothing else may hold one`. Two seals carry that,
   and this prints where they stand.

   It reports MOVEMENT, not just state. A status update whose number never moves says nothing about
   whether the work is working, so every run compares against a recorded baseline and prints the
   delta. `--record-baseline` writes the file; do it once at the start of the phase and leave it.

   Output is GitHub-flavoured markdown on stdout, sized for a PR comment."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [fukan.repl :as repl]
            [fukan.cozo.law :as law]
            [fukan.canvas.core.structure :as s]
            [fukan.infra.model :as infra-model]))

(def ^:private default-baseline
  "Where the baseline lives when `--baseline` is not given. Relative to fukan, which is only ever
   right when running by hand from this checkout — brian's shim always passes its own."
  ".phase-1-baseline.edn")

;; ── reading the model ────────────────────────────────────────────────────────

(defn- law-rows
  "Offender rows for the law whose description contains `substr`, as vectors of labels."
  [db substr]
  (let [desc (->> (:laws (s/structure-by-tag :fukan.common.vocab.code.region/Region))
                  (map :desc) (filter #(str/includes? % substr)) first)]
    (->> (law/check db) (filter #(= desc (:law %))) (mapcat :offenders)
         (map (fn [row] (mapv #(law/offender-label db %) row)))
         set)))

(defn measure
  "The Phase-1 numbers for the model held after `go`."
  [db]
  (let [seals (law-rows db "sealed region")
        conf  (law-rows db "cross-region")
        by    (group-by #(nth % 2) seals)]
    {:connection-seal     (count (get by "Execute" []))
     :persistence-seal    (count (get by "Persistence" []))
     :persistence->domain (count conf)
     :unclaimed           (count (law-rows db "belongs to a region"))
     :rows                seals
     :conf-rows           conf}))

;; ── formatting ───────────────────────────────────────────────────────────────

(defn- delta
  "`n` against its baseline: a signed movement, or nothing to say."
  [n base]
  (cond
    (nil? base)  ""
    (< n base)   (format " **▼ %d** from %d" (- base n) base)
    (> n base)   (format " **▲ %d** from %d — going the wrong way" (- n base) base)
    :else        (format " unchanged from %d" base)))

(defn- callers
  "Offending namespaces for one seal, commonest first: [ns crossings]."
  [rows seal]
  (->> rows (filter #(= seal (nth % 2)))
       (map first) frequencies
       (sort-by (juxt (comp - val) key))))

(defn- top-table [rows seal n]
  (let [cs (callers rows seal)]
    (when (seq cs)
      (str "| namespace | crossings |\n|---|---:|\n"
           (str/join "\n" (for [[ns c] (take n cs)] (format "| `%s` | %d |" ns c)))
           (when (> (count cs) n)
             (format "\n| … %d more | |" (- (count cs) n)))))))

(defn render
  "The markdown body."
  [{:keys [connection-seal persistence-seal persistence->domain unclaimed rows]} base focus]
  (let [base (when-not focus base)          ; see the note printed for a focused run
        b #(get base % nil)
        done? (and (zero? connection-seal) (zero? persistence-seal))]
    (str
     "## Phase 1 — Domain becomes the door\n\n"
     (if done?
       "**Both seals hold.** Nothing outside Persistence reaches the connection, and nothing reaches Persistence without a declared edge.\n\n"
       "Phase 1 is the absence of one arrow: Transport and Presentation reach Persistence *through Domain*, and nothing but Persistence holds a connection. These are the crossings still in the way.\n\n")
     (when focus
       (format (str "> Filtered to `%s`. Counts below are this slice only, and carry NO comparison:\n"
                    "> the recorded baseline is for the whole phase, so a delta against it would\n"
                    "> read the filter as progress.\n\n")
               focus))
     "| seal | what it forbids | crossings |\n|---|---|---:|\n"
     (format "| **Execute** | anything but Persistence holding a connection | **%d**%s |\n"
             connection-seal (delta connection-seal (b :connection-seal)))
     (format "| **Persistence** | reaching Persistence without a declared edge | **%d**%s |\n"
             persistence-seal (delta persistence-seal (b :persistence-seal)))
     "\n"
     (format "Persistence → Domain (the contract inverted): **%d**%s\n\n"
             persistence->domain (delta persistence->domain (b :persistence->domain)))
     (when-let [t (top-table rows "Execute" 10)]
       (str "<details><summary>Holding a connection (" connection-seal ")</summary>\n\n" t "\n\n</details>\n\n"))
     (when-let [t (top-table rows "Persistence" 10)]
       (str "<details><summary>Reaching Persistence unlicensed (" persistence-seal ")</summary>\n\n" t "\n\n</details>\n\n"))
     (format "<sub>%d of brian's namespaces are not claimed by a declared region; a seal needs only its TARGET claimed, so those callers are still counted above. fukan `Region` laws, %s.</sub>\n"
             unclaimed (str (java.time.LocalDate/now))))))

;; ── entry ────────────────────────────────────────────────────────────────────

(defn- focus-filter
  "Narrow every count to the slice `focus` names — BOTH ends of an edge, so an iteration sees the
   crossings it owns whether it is the caller or the thing reached. Conformance rows narrow with
   the seals; leaving them global would report someone else's finding on your PR."
  [m focus]
  (if-not focus
    m
    (let [keep? (fn [row] (or (str/starts-with? (first row) focus)
                              (str/starts-with? (second row) focus)))
          rows  (set (filter keep? (:rows m)))
          conf  (set (filter keep? (:conf-rows m)))
          by    (group-by #(nth % 2) rows)]
      (assoc m :rows rows :conf-rows conf
               :connection-seal     (count (get by "Execute" []))
               :persistence-seal    (count (get by "Persistence" []))
               :persistence->domain (count conf)))))

(defn -main [& args]
  (let [{:strs [--src --focus --record-baseline --baseline]}
        (apply hash-map (concat args (when (odd? (count args)) [nil])))
        baseline-file (or --baseline default-baseline)
        src    (or --src (System/getenv "BRIAN_SRC"))
        _      (when-not src
                 (binding [*out* *err*]
                   (println "phase-1-report: no --src given and BRIAN_SRC unset"))
                 (System/exit 2))
        ;; stdout is the DELIVERABLE — it gets piped into a PR comment — so the build's own
        ;; chatter goes to stderr, where a human running this by hand still sees it.
        _      (binding [*out* *err*]
                 (repl/go {:src src :title "brian" :spec-dirs ["brian"]}))
        m      (focus-filter (measure (infra-model/get-model)) --focus)]
    (if --record-baseline
      (do (io/make-parents baseline-file)
          (spit baseline-file (with-out-str (pp/pprint (-> m (dissoc :rows)
                                                           (assoc :recorded (str (java.time.LocalDate/now)))))))
          (println "baseline written to" baseline-file))
      (let [base (when (.exists (io/file baseline-file))
                   (edn/read-string (slurp baseline-file)))]
        (println (render m base --focus))))
    (shutdown-agents)))
