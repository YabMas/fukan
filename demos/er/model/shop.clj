(ns demos.er.model.shop
  "A small shop schema modelled with the ER vocabulary:

     User    { name:String, email:String }
     Product { title:String, price:Int }
     Order   { total:Int }  --placed-by--> User   --contains--> Product

   Acyclic (Order references User and Product; neither references back) so it is
   authorable under forward-only resolution."
  (:require [fukan.canvas.core.structure :as s]
            [demos.er.vocab.core :refer [DataType Attribute Relationship Entity]]))

(defn build []
  (s/with-structures
    (s/within-module "shop"
      (DataType "String")
      (DataType "Int")
      ;; User
      (Attribute "name"  (type String) (required true))
      (Attribute "email" (type String) (required true) (unique? true))
      (Entity "User" (attr name) (attr email))
      ;; Product
      (Attribute "title" (type String) (required true))
      (Attribute "price" (type Int)    (required true))
      (Entity "Product" (attr title) (attr price))
      ;; Order — references User and Product
      (Attribute "total" (type Int))
      (Relationship "placed-by" (target User))
      (Relationship "contains"  (target Product))
      (Entity "Order" (attr total) (rel placed-by) (rel contains)))))
