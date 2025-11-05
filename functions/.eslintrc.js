module.exports = {
  env: {
    es6: true,
    node: true,
  },
  parserOptions: {
    "ecmaVersion": 2018,
  },
  extends: [
    "eslint:recommended",
    "google",
  ],
  rules: {
     'linebreak-style': 'off',
         'quotes': 'off',
         'max-len': 'off',
         'object-curly-spacing': 'off',
         'indent': 'off',
         'require-jsdoc': 'off',
  },
  overrides: [
    {
      files: ["**/*.spec.*"],
      env: {
        mocha: true,
      },
      rules: {},
    },
  ],
  globals: {},
};
