// emailController.js

// AngularJS modul létrehozása
var app = angular.module('emailApp', []);

// Kontroller definíciója
app.controller('EmailController', ['$scope', '$http', function($scope, $http) {
  // Email entitások tömbje
  $scope.emails = [];

  // Email entitások lekérdezése backendről
  $http.get('/api/emails')
    .then(function(response) {
      $scope.emails = response.data;
    }, function(error) {
      console.error('Hiba történt az email adatok lekérése közben: ', error);
    });

  // Email entitás részleteinek megjelenítése
  $scope.showDetails = function(email) {
    $scope.selectedEmail = email;
  };

  // Email entitás részleteinek elrejtése
  $scope.hideDetails = function() {
    $scope.selectedEmail = null;
  };
}]);
