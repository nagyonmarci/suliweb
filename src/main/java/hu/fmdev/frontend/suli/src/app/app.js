// js/app.js

var app = angular.module('myApp', []);

app.controller('EmailController', ['$http', function($http) {
  var vm = this;

  vm.email = {};

  vm.getAllEmails = function() {
    $http.get('/api/emails')
      .then(function(response) {
        vm.emails = response.data;
      })
      .catch(function(error) {
        console.error('Error fetching emails:', error);
      });
  };

  vm.saveOrUpdateEmail = function() {
    if (vm.email.id) {
      $http.put('/api/emails/' + vm.email.id, vm.email)
        .then(function(response) {
          vm.getAllEmails();
          vm.email = {};
        })
        .catch(function(error) {
          console.error('Error updating email:', error);
        });
    } else {
      $http.post('/api/emails', vm.email)
        .then(function(response) {
          vm.getAllEmails();
          vm.email = {};
        })
        .catch(function(error) {
          console.error('Error adding email:', error);
        });
    }
  };

  vm.selectEmail = function(emailId) {
    $http.get('/api/emails/' + emailId)
      .then(function(response) {
        vm.selectedEmail = response.data;
      })
      .catch(function(error) {
        console.error('Error fetching selected email:', error);
      });
  };

  vm.deleteEmail = function(emailId) {
    $http.delete('/api/emails/' + emailId)
      .then(function(response) {
        vm.getAllEmails();
        vm.selectedEmail = null;
      })
      .catch(function(error) {
        console.error('Error deleting email:', error);
      });
  };

  vm.getAllEmails();
}]);
