(ns fukan.canvas.library.monolith
  "First lift library for fukan: function, record.
   Captures the monolith architecture's vocabulary."
  (:require [fukan.canvas.defconstructor :refer [defconstructor]]
            [fukan.canvas.helpers :as h]
            [fukan.canvas.substrate :as sub]
            [fukan.canvas.substrate.store :as store]))

(defconstructor function
  "A synchronous function call: takes inputs, gives output, may have effects."

  (form takes  "Input parameters."        :shape :field+)
  (form gives  "Return value shape."      :shape :type-ref :required true)
  (form effect "An effect this performs." :shape :name-ref :repeatable true)

  (produces [name doc forms]
    (let [takes-args  (first (:takes forms))        ; e.g. [email :String]
          gives-arg   (first (:gives forms))        ; e.g. :Account
          field-pairs (if takes-args
                        (vec (partition 2 takes-args))
                        [])
          inputs      (h/record-of field-pairs)
          outputs     gives-arg
          aff         (h/declare-affordance name
                        :shape (h/arrow inputs outputs)
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
          field-pairs (mapv (fn [args] [(first args) (second args)]) field-args)
          t (sub/type-record name field-pairs)]
      (swap! h/*store* store/transact! t))))
