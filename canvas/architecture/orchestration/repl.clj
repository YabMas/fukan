(ns canvas.architecture.orchestration.repl
  "Self-spec: `fukan.repl` — the shipped COCKPIT, the consumer surface an external project drives
   its model with. `go` configures + builds; `refresh`/`reset` reload changed code (via clj-reload,
   external-by-design — not modelled) and rebuild the held model through the `infra-model` facade;
   `status` reads the held model back through the kernel query primitive. `config` is a pure read
   of the held cockpit state, delegating nothing.

   The remaining fourteen are the READ commands — the model-explorer surface, all reading the held
   model back out through `infra-model/get-model` and one of the query/projection/law faculties:
   `architecture`/`grammar`/`correspondence` render the design (projection); `show`/`focus` render
   nodes/foci as authored forms (projection.instance); `check`/`drift`/`encapsulation`/`type-drift`
   read violations and correspondence worklists (cozo.law); `deps`/`purity`/`leaves`/`frontier`/
   `undeclared-code-dependencies` read the graph directly (cozo.query). These queries are
   tag-driven and project-agnostic — any consumer using the `lib.code` vocabulary gets them
   unchanged.

   fukan-on-itself is the FIRST consumer of this surface (`dev/user.clj` wraps it with fukan's own
   defaults), so `repl` sits in `orchestration` beside `infra-model` and `core` — the lifecycle +
   composition-root cluster."
  (:require [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.orchestration.infra :as infra]
            [canvas.architecture.cozo.query :as cozo-query]
            [canvas.architecture.cozo.law :as law]
            [canvas.architecture.projection.grammar :as gram]
            [canvas.architecture.projection.instance :as inst]
            [canvas.architecture.projection.architecture :as arch]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.structure :as structure]
            [canvas.architecture.ingestion.extraction :as extraction]))

(Module repl
  "The COCKPIT — the shipped reader/lifecycle commands a project drives its model with."
  (Operation config "The held cockpit config — {:src :title :spec-dirs :reload-dirs} — or nil before `go`."
    {:signature [:=> [:cat] :any]})
  (Operation go
    "Build (or rebuild) the held Model and hold the cockpit config (:src :title :spec-dirs
     :reload-dirs). `go` IS the configure step — there is no separate configure! call: it holds
     the config every other command reads, so the commands themselves stay bare."
    {:signature [:=> [:catn [:opts :any]] substrate/StructureDb]
     :performs  [:io :require :state :throws]
     :delegates [infra/load-model]})
  (Operation reset "Reload changed code, then rebuild the held Model from the held config."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :require :state :throws]
     :delegates [infra/load-model]})
  (Operation refresh "Reload changed code + rebuild the held Model. Use after editing a spec."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :require :state :throws]
     :delegates [infra/load-model]})
  (Operation status "Print the held Model's structure/relation counts and src, or that none is held."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model cozo-query/q infra/get-src]})
  (Operation architecture
    "Print the projected SYSTEM MAP — the project's code-side architecture, its subsystems, their
     modules, and the :may-depend DAG, derived live from the held Model. Bare: the banner's title
     is the `:title` held by `go`."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model arch/architecture-overview]})
  (Operation grammar
    "Print the GRAMMAR PRIMER — every vocabulary in the held Model rendered back as its map-form
     defstructures (or, given a vocabulary name, that one vocabulary alone)."
    {:signature [:=> [:catn [:vocab-name :string]] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model gram/grammar-primer gram/vocabulary-primer]})
  (Operation correspondence
    "Print the CORRESPONDENCE CARD — every registered essential `(correspond …)` plus its live
     coverage readings over the held Model, and the unaccounted-public count."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model gram/correspondence-card law/violations-of]})
  (Operation show
    "Print every Model node named `n` as its AUTHORED form — the instance print-dual."
    {:signature [:=> [:catn [:n [:or :string :symbol]]] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model cozo-query/q inst/focus-text]})
  (Operation focus
    "Evaluate datalog `clauses` over the held Model and print the focused nodes as their authored
     forms — the textual model explorer."
    {:signature [:=> [:catn [:clauses [:vector :any]]] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model inst/focus-text]})
  (Operation check
    "Run every law over the held Model and print the violations, each offender quoted as its
     authored form."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model law/check inst/violations-text]})
  (Operation drift
    "Model↔code drift in the held Model at both altitudes: modelled Modules no namespace realizes,
     and modelled Operations no function realizes."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model law/violation-rows]})
  (Operation undeclared-code-dependencies
    "Cross-subsystem dependencies present in CODE (`realized-delegates`, rolled up to subsystem
     altitude) that no declared :may-depend edge covers. A signal, not a law."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model cozo-query/q structure/vocab-rules]})
  (Operation encapsulation
    "The ENCAPSULATION worklist: PUBLIC extracted functions inside an ADOPTED namespace that no
     authored Operation models, grouped by code namespace."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model law/violation-rows]})
  (Operation leaves
    "The ADOPTION CANDIDATES: unadopted namespaces depending on no other namespace in the project,
     ranked by fan-in — where leaf-upward adoption starts."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model]})
  (Operation frontier
    "The ADOPTION FRONTIER: calls from adopted code out into code the model does not yet claim,
     grouped by unadopted callee and ranked by how many adopted namespaces reach it."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model]})
  (Operation deps
    "Print the project's complete module→module dependency graph (calls ∪ data-adoption), then any
     DECLARED subsystem :may-depend edges the code does NOT realize."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model]})
  (Operation purity
    "The EFFECT SURFACE: extracted functions that DIRECTLY perform a consequential effect,
     grouped by code namespace."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model cozo-query/q structure/vocab-rules]})
  (Operation type-drift
    "TYPE adherence (model↔code): paired Operations whose signature DISAGREES with the code's
     `:malli/schema`."
    {:signature [:=> [:cat] extraction/Unit]
     :performs  [:io :throws :state]
     :delegates [infra/get-model law/violation-rows]}))
