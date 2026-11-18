# SkeletonFrontend

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 21.0.2.

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Choosing the app by hostname

`public/assets/config.json` maps the hostname the browser used to an app code. The port is ignored.

```json
{ "apiBasePath": "/api", "hosts": { "localhost": "GRIEVANCE", "asset.localhost": "ASSET" } }
```

The backend publishes every file in `examples/` at startup, so both apps are always available. To open the asset
register instead of the grievance tracker, browse to `http://asset.localhost` (with `docker compose up`, or
`http://asset.localhost:4200` with `ng serve`). Browsers resolve `*.localhost` to 127.0.0.1 without any setup. Any
other hostname works too: add it to `hosts` and point DNS or the Windows `hosts` file at the machine.
`http://localhost/app/ASSET` also opens the asset register directly.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
