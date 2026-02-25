app.controller('FileController', ['$scope', '$http', function($scope, $http) {

  $scope.indexDirectory = function(path, exclude) {
    var params = {
      path: path,
      exclude: exclude
    };

    $http.get('/index', { params: params })
      .then(function(response) {
        alert('Sikeres indexelés: ' + response.data);
      }, function(error) {
        alert('Hiba történt: ' + error.data);
      });
  };

}]);
