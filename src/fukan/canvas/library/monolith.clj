(ns fukan.canvas.library.monolith
  "First lift library for fukan: function, record.
   Captures the monolith architecture's vocabulary."
  (:require [fukan.canvas.defconstructor :refer [defconstructor]]
            [fukan.canvas.helpers :as h]
            [fukan.canvas.shape :as shape]
            [fukan.canvas.substrate :as sub]
            [fukan.canvas.substrate.store :as store]))

(defconstructor function
  "A synchronous function call: takes inputs, gives output, may have effects."

  (form takes  "Input parameters."        :shape :field+)
  (form gives  "Return value shape."      :shape :type-ref :required true)
  (form effect "An effect this performs." :shape :name-ref :repeatable true)

  (produces [name doc forms]
    (let [takes-args  (first (:takes forms))        ; e.g. [email :String]
          gives-arg   (first (:gives forms))        ; e.g. :Account or (optional :Account)
          field-pairs (if takes-args
                        (vec (->> (partition 2 takes-args)
                                  (mapv (fn [[n s]] [n (shape/parse s)]))))
                        [])
          inputs      {:kind :record :fields field-pairs}
          outputs     (shape/parse gives-arg)
          aff         (h/declare-affordance name
                        :shape {:kind :arrow :inputs inputs :outputs outputs}
                        :role :fukan.canvas.monolith/exposed-call)]
      (doseq [e-args (:effect forms)]
        (h/declare-relation (:id aff)
                            :fukan.canvas.monolith/performs
                            (first e-args))))))

(defconstructor record
  "An owned data type with named typed fields."

  (form field "A named typed field." :shape :field-pair :repeatable true :required true)

  (produces [name doc forms]
    (let [field-args  (:field forms)
          field-pairs (mapv (fn [args] [(first args) (shape/parse (second args))]) field-args)
          t (sub/type-record name field-pairs)]
      (swap! h/*store* store/transact! t))))

(defconstructor value
  "An opaque named type — a named concept whose internal structure is withheld.
   Use for Allium-style value declarations with no exposed fields."

  (produces [name doc forms]
    (let [t (sub/type-primitive name)]
      (swap! h/*store* store/transact! t))))
