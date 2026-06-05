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
(def email     (Attribute "email" (type StrType) (required true) (unique? true)))
(def User      (Entity "User" (attr attr-name) (attr email)))

;; Product
(def title   (Attribute "title" (type StrType) (required true)))
(def price   (Attribute "price" (type IntType) (required true)))
(def Product (Entity "Product" (attr title) (attr price)))

;; Order — references User and Product
(def total     (Attribute "total" (type IntType)))
(def placed-by (Relationship "placed-by" (target User)))
(def contains  (Relationship "contains"  (target Product)))
(def Order     (Entity "Order" (attr total) (rel placed-by) (rel contains)))

(defn build [] (a/assemble ['demos.er.model.shop]))
