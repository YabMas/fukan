(ns fukan.canvas.project.render
  "Structured projection → markdown rendering.

   The implementing LLM consumes both the structured projection map and
   a markdown rendering of it. This namespace produces the markdown
   form. Layer B (scenarios) wraps the same projection map with
   situation framing; Layer A's markdown stays terse — title, address,
   prose envelope, code block. No scenario context, no neighbor
   suggestions."
  (:require [clojure.string :as str]))

(defn- code-fence
  "Wrap `body` in a fenced code block tagged with `lang` (e.g. \"clojure\")."
  [lang body]
  (str "```" lang "\n" body "\n```"))

(defn- target-line
  [{:keys [path namespace symbol]}]
  (cond-> (str "- **Target:** `" path "`")
    namespace (str " — `" namespace "/" symbol "`")
    (and (nil? namespace) symbol) (str " — `" symbol "`")))

(defn to-markdown
  "Render one projection map as markdown. Sections:
     # <projection-kind>
     - target / element-id
     <prose>
     ```<lang>
     <template>
     ```

   When the projection has no template (`:template nil`), the code-block
   section is omitted. The lens-id is used as the code-fence language
   tag, which gives a clojure projection a `clojure` fence by
   convention."
  [{:keys [projection-kind lens-id model-element-id model-element-kind
           target template prose context]
    :as _projection}]
  (let [lang (when lens-id (name lens-id))]
    (str/join
     "\n\n"
     (cond-> [(str "# " (subs (str projection-kind) 1))]
       true       (conj (str/join "\n"
                                  (cond-> [(target-line target)
                                           (str "- **Element:** " model-element-id
                                                " (" (name model-element-kind) ")")]
                                    (and (map? context) (:canvas-source-ref context))
                                    (conj (str "- **Source:** " (:canvas-source-ref context))))))
       (seq prose) (conj prose)
       template   (conj (code-fence (or lang "text") template))))))
