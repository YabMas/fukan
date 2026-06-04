(ns fukan.target.correspondence
  "The model↔code CORRESPONDENCE concern — deliberately separate from BOTH the
   abstract modelling domain (canvas/, e.g. `Stage`) and the code-structure domain
   (`Operation`). A domain definition's laws should concern only that domain's own
   behaviour and constraints; HOW the model is realized in code is an orthogonal
   question. Keeping it here means each can be reasoned about — and evolved — in
   isolation: focus on the domain without implementation noise, and focus on the
   implementation/correspondence question without touching the domains.

   It holds fukan's self-correspondence. fukan's projection convention is that an
   op-layer `Stage` named X is realized by a function named X, so the law matches
   purely by name across the altitude gap between authored Stages (canvas/) and
   extracted Operations (src/) — no `realizes` relation needed. The law references
   `:Stage` and `:Operation` only as data (structure tags resolved at check-time
   over the merged graph), so this concern takes no code dependency on either
   domain."
  (:require [datascript.core :as d]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defstructure Realization
  "A law-holder for the model↔code correspondence — it has no instances of its own;
   it exists to carry the cross-layer assertion in its own concern.

   `:scope :global` opts out of the default self-scoping (which would inject
   `[?s :structure/of :Realization]` and make the law vacuous, since its offenders
   are Stages, not Realizations). The leading Operation clause is the real guard:
   the law is vacuous when no code is extracted — correspondence is only assertable
   when both layers share the graph — so registering it never disturbs a model-only
   `check`."
  (law "every modelled Stage is realized by an Operation of the same name"
    :scope :global
    :offenders '[?s]
    :where '[[?o :structure/of :Operation]
             [?s :structure/of :Stage] [?s :entity/name ?n]
             (not [?o2 :structure/of :Operation] [?o2 :entity/name ?n])]))

(defn unrealized-stages
  "The modelled Stages in `db` with no same-named extracted Operation, as a set of
   names. Empty ⇔ the model is fully realized in code. The focusable surface of the
   correspondence concern; reads the single source of truth (the registered law)."
  [db]
  (let [desc (-> (s/structure-by-tag :Realization) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity db %)))
         set)))
