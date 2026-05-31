;; Project-local agent views fixture
(defn unrealised-by-altitude
  "Group absent projections by source primitive kind."
  []
  (->> (:rows (relations :kind :projects :validity :absent))
       (map (fn [e] (assoc e :primitive (get-primitive (-> e :from :endpoint/primitive)))))
       (group-by (comp :kind :primitive))))
