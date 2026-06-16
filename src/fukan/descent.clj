(ns fukan.descent
  "The GENERATIVE readings of a descent step — the UP (carve) and GAP (prompt) readings of the
   structural-witness obligation, complementing the DOWN (verify) reading that the law itself is.
   First slice: the `canvas.subject/Source` in-fold (its realization edge + witness law live in
   `canvas.descent.source`). `required-witnesses` reads the carved space off the reflected `Source`
   portrait; `unwitnessed-polarities` is the gap worklist — the same set the witness law reports as
   offenders, surfaced as plain data. Reads the reflected graph only; references no canvas code."
  (:require [clojure.set :as set]
            [datascript.core :as d]))

(defn required-witnesses
  "UP (carve): the polarity flavours the reflected `Source` portrait declares — the design space a
   realizer must fill. Empty when the subject is not reflected into `db`."
  [db]
  (->> (d/q '[:find ?v
              :where [?src :val/tag ":canvas.subject/Source"]
                     [?pr :rel/from ?src] [?pr :rel/label "polarity"] [?pr :rel/to ?enum]
                     [?cr :rel/from ?enum] [?cr :rel/kind :choice] [?cr :rel/to ?c]
                     [?c :val/value ?v]]
            db)
       (map first) set))

(defn declared-witnesses
  "The polarity flavours actually witnessed by a `SourceRealizer` in `db`. Empty when no
   `SourceRealizer` instances exist in `db`."
  [db]
  (->> (d/q '[:find ?v
              :where [?w :structure/of :canvas.descent.source/SourceRealizer]
                     [?w :val/witnesses ?v]]
            db)
       (map first) set))

(defn unwitnessed-polarities
  "GAP (prompt): the carved space minus what is declared — the descent worklist. Empty ⇔ the
   `Source` in-fold is fully realized. (The same set the witness law reports as offenders.)"
  [db]
  (set/difference (required-witnesses db) (declared-witnesses db)))
