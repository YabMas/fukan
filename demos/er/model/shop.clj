(ns demos.er.model.shop
  "A small shop schema modelled with the ER vocabulary:

     User    { name:String, email:String }
     Product { title:String, price:Int }
     Order   { total:Int }  --placed-by--> User   --contains--> Product

   Acyclic (Order references User and Product; neither references back) so it is
   authorable under forward-only resolution."
  (:require [fukan.canvas.core.assemble :as a]
            [demos.er.vocab.core :refer [DataType Attribute Relationship Entity]]))

(DataType ^{:name "String"} StrType)
(DataType ^{:name "Int"} IntType)

;; User
(Attribute ^{:name "name"} attr-name {:type StrType :required true})
(Attribute email {:type StrType :required true :unique? true})
(Entity User {:attr [attr-name email]})

;; Product
(Attribute title {:type StrType :required true})
(Attribute price {:type IntType :required true})
(Entity Product {:attr [title price]})

;; Order — references User and Product
(Attribute total {:type IntType})
(Relationship placed-by {:target User})
(Relationship contains {:target Product})
(Entity Order {:attr [total] :rel [placed-by contains]})

(defn build [] (a/assemble ['demos.er.model.shop]))
