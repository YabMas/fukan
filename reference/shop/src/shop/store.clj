(ns shop.store
  "Where the shop's facts are kept: in memory, by entity and id. It reads what a view asks for
   and nothing else. Lexicon declares and never evaluates; this is the evaluating."
  (:require [lexicon.core :as lexicon]
            [lexicon.install :as install]
            [malli.experimental :as mx]
            [shop.domain]))

(defonce ^:private facts (atom {}))

(mx/defn put! :- :nil
  "Keep `row`, an entity's attributes, under the id it carries."
  [entity :- :keyword, row :- [:map-of :keyword :any]]
  (swap! facts assoc-in [entity (get row (keyword (name entity) "id"))] row)
  nil)

(mx/defn pull :- [:maybe [:map-of :keyword :any]]
  "The entity with `id`, as the view named `view` selects it: its own attributes, and those
   of another entity reached through its one reference to it."
  [view :- :keyword, id :- :int]
  (let [v    (install/view view)
        root (lexicon/root-of v)]
    (when-let [row (get-in @facts [root id])]
      (into {}
            (for [{:keys [key kind via]} (lexicon/items-of v)]
              [key (case kind
                     :own  (get row key)
                     :path (get-in @facts [(keyword (namespace key)) (get row via) key]))])))))

(mx/defn find-one :- [:maybe [:map-of :keyword :any]]
  "The one entity whose attribute `attribute` holds `value`, as the view named `view` selects
   it; nothing when there is none."
  [view :- :keyword, attribute :- :keyword, value :- :any]
  (let [root (lexicon/root-of (install/view view))
        id   (keyword (name root) "id")]
    (some (fn [row] (when (= value (get row attribute)) (pull view (get row id))))
          (vals (get @facts root)))))

(mx/defn clear! :- :nil
  "Forget everything."
  []
  (reset! facts {})
  nil)
