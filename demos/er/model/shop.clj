(ns demos.er.model.shop
  "A small shop schema modelled with the ER vocabulary:

     User    { name:String, email:String }
     Product { title:String, price:Int }
     Order   { total:Int }  --placed-by--> User   --contains--> Product

   Acyclic (Order references User and Product; neither references back) so it is
   authorable under forward-only resolution."
  (:require [fukan.canvas.core.assemble :as a]
            [demos.er.vocab.core :refer [DataType Attribute Relationship Entity]]))

(def StrType (DataType "String"))
(def IntType (DataType "Int"))

;; User
(def attr-name (Attribute "name"  (type StrType) (required true)))
(def email     (Attribute (type StrType) (required true) (unique? true)))
(def User      (Entity (attr attr-name) (attr email)))

;; Product
(def title   (Attribute (type StrType) (required true)))
(def price   (Attribute (type IntType) (required true)))
(def Product (Entity (attr title) (attr price)))

;; Order — references User and Product
(def total     (Attribute (type IntType)))
(def placed-by (Relationship (target User)))
(def contains  (Relationship (target Product)))
(def Order     (Entity (attr total) (rel placed-by) (rel contains)))

(defn build [] (a/assemble ['demos.er.model.shop]))
